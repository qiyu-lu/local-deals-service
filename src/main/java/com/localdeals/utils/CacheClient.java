package com.localdeals.utils;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.localdeals.observability.LocalDealsMetrics;
import org.springframework.boot.autoconfigure.cache.CacheProperties;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.localdeals.utils.RedisConstants.*;

@Component
public class CacheClient {
    private final StringRedisTemplate stringRedisTemplate;
    private final LocalDealsMetrics metrics;

    private static final ExecutorService CACHE_REBUILD_EXECUTOR =
            Executors.newFixedThreadPool(10);

    public CacheClient(StringRedisTemplate stringRedisTemplate, LocalDealsMetrics metrics) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.metrics = metrics;
    }

    //方法1：将任意Java对象序列化为json并存储在string类型的key中，并且可以设置TTL过期时间
    public void set(String key, Object value, Long time, TimeUnit unit) {
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value),time, unit);
    }

    // 方法2：将任意Java对象序列化为json并存储在string类型的key中，并且可以设置逻辑过期时间，用于处理缓存击穿问题
    public void setWithLogicExpire(String key, Object value, Long time, TimeUnit unit){
        RedisData redisData = new RedisData();
        redisData.setData(value);
        //if(unit == TimeUnit.MINUTES)  redisData.setExpireTime(LocalDateTime.now().plusMinutes(Time));
        redisData.setExpireTime(
                LocalDateTime.now().plusSeconds(unit.toSeconds(time))
        );
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }

    // 方法3：根据指定的key查询缓存，并反序列化为指定类型，利用缓存空值的方式解决缓存透问题
    public <T, ID> T queryWithPassThrough(
            LocalDealsMetrics.CacheResource resource,
            String keyPrefix,//缓存工具类是通用的,业务层只关心：id, key 的拼接规则由缓存层统一控制
            ID id,//业务对象的 唯一标识 使用泛型 ID
            Class<T> type,//反序列化时的目标类型
            Function<ID, T> dbFallback,//当缓存未命中时，如何查询数据库
            Long time,//缓存过期时间的数值部分
            TimeUnit unit) {//时间单位
        String cacheKey = keyPrefix + id.toString();
        final String cacheValue;
        try {
            cacheValue = stringRedisTemplate.opsForValue().get(cacheKey);
        } catch (RuntimeException redisFailure) {
            metrics.recordCache(resource, LocalDealsMetrics.CacheResult.REDIS_ERROR);
            throw redisFailure;
        }

        if(StrUtil.isNotBlank(cacheValue)){
            if(EMPTY_PLACEHOLDER.equals(cacheValue)){
                metrics.recordCache(resource, LocalDealsMetrics.CacheResult.EMPTY_HIT);
                return null;
            }
            metrics.recordCache(resource, LocalDealsMetrics.CacheResult.HIT);
            return JSONUtil.toBean(cacheValue, type);
        }
        metrics.recordCache(resource, LocalDealsMetrics.CacheResult.MISS);
        long startedAt = System.nanoTime();
        final T res;
        try {
            res = dbFallback.apply(id);
            metrics.recordCache(resource, res == null
                    ? LocalDealsMetrics.CacheResult.DB_EMPTY
                    : LocalDealsMetrics.CacheResult.DB_SUCCESS);
        } catch (RuntimeException databaseFailure) {
            metrics.recordCache(resource, LocalDealsMetrics.CacheResult.DB_ERROR);
            throw databaseFailure;
        } finally {
            metrics.recordCacheDbFallback(resource, System.nanoTime() - startedAt);
        }
        if(res == null){
            try {
                stringRedisTemplate.opsForValue().set(cacheKey, EMPTY_PLACEHOLDER, time, unit);
            } catch (RuntimeException redisFailure) {
                metrics.recordCache(resource, LocalDealsMetrics.CacheResult.REDIS_ERROR);
                throw redisFailure;
            }
            return null;
        }
        try {
            this.set(cacheKey, res, time, unit);
        } catch (RuntimeException redisFailure) {
            metrics.recordCache(resource, LocalDealsMetrics.CacheResult.REDIS_ERROR);
            throw redisFailure;
        }
        // stringRedisTemplate.opsForValue().set(cacheKey, JSONUtil.toJsonStr(res), time, unit);
        return res;
    }

    // 方法4：根据指定的key查询缓存，并反序列化为指定类型，需要利用逻辑过期解决缓存击穿问题
    public <T, ID> T queryWithLogicalExpire(
            String keyPrefix,
            ID id,
            Class<T> type,
            Function<ID, T> dbFallback,
            Long time,
            TimeUnit unit)
    {
        String cacheKey = keyPrefix + id.toString();
        String cacheValue = stringRedisTemplate.opsForValue().get(cacheKey);
        if (StrUtil.isBlank(cacheValue)) { return null; }
        RedisData redisData = JSONUtil.toBean(cacheValue, RedisData.class);
        if(redisData.getData() == null){
            return null;
        }
        T res = JSONUtil.toBean((JSONObject) redisData.getData(), type);
        //没有过期
        if(redisData.getExpireTime().isAfter(LocalDateTime.now())){
            return res;
        }

        //过期了,尝试重建
        String lockKey = "lock:" + keyPrefix + id.toString();
        boolean locked = Boolean.TRUE.equals(
                stringRedisTemplate.opsForValue()
                        .setIfAbsent(lockKey, "1", LOCK_SHOP_TTL, TimeUnit.SECONDS)
        );
        if(locked){
            CACHE_REBUILD_EXECUTOR.submit(()->{
                try{
                    T shop = dbFallback.apply(id);
                    this.setWithLogicExpire(keyPrefix+id, shop, time, unit);
//                    RedisData temp = new RedisData();
//                    temp.setData(shop);
//                    temp.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));
//                    stringRedisTemplate.opsForValue().set(keyPrefix+id.toString(), JSONUtil.toJsonStr(temp));
                }
                catch (Exception e){ throw  new RuntimeException(e); }
                finally {
                    stringRedisTemplate.delete(lockKey);
                }
            });
        }
        return res;
    }
}
