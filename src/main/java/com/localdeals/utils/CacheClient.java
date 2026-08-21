package com.localdeals.utils;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.localdeals.config.BoundedCacheProperties;
import com.localdeals.observability.LocalDealsMetrics;
import com.localdeals.service.LocalReadBulkhead;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiPredicate;
import java.util.function.Function;
import java.util.function.Supplier;

import static com.localdeals.utils.RedisConstants.*;

@Component
@Slf4j
public class CacheClient {
    private static final long WRITE_WARNING_INTERVAL_NANOS = TimeUnit.SECONDS.toNanos(30);

    private final StringRedisTemplate stringRedisTemplate;
    private final LocalDealsMetrics metrics;
    private final BoundedCacheProperties properties;
    private final SingleFlightLoader singleFlightLoader;
    private final LocalReadBulkhead localReadBulkhead;
    private final AtomicLong lastWriteWarningAt = new AtomicLong();

    private static final ExecutorService CACHE_REBUILD_EXECUTOR =
            Executors.newFixedThreadPool(10);

    public CacheClient(StringRedisTemplate stringRedisTemplate,
                       LocalDealsMetrics metrics,
                       BoundedCacheProperties properties,
                       SingleFlightLoader singleFlightLoader,
                       LocalReadBulkhead localReadBulkhead) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.metrics = metrics;
        this.properties = properties;
        this.singleFlightLoader = singleFlightLoader;
        this.localReadBulkhead = localReadBulkhead;
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
            BiPredicate<ID, T> cacheValueValidator) {
        String cacheKey = keyPrefix + id.toString();
        String cacheValue;
        try {
            cacheValue = stringRedisTemplate.opsForValue().get(cacheKey);
        } catch (RuntimeException redisFailure) {
            metrics.recordCache(resource, LocalDealsMetrics.CacheResult.REDIS_ERROR);
            return loadObject(resource, cacheKey, id, dbFallback, true);
        }

        if (cacheValue == null) {
            metrics.recordCache(resource, LocalDealsMetrics.CacheResult.MISS);
            return loadObject(resource, cacheKey, id, dbFallback, false);
        }
        if (EMPTY_PLACEHOLDER.equals(cacheValue)) {
            metrics.recordCache(resource, LocalDealsMetrics.CacheResult.EMPTY_HIT);
            return null;
        }
        try {
            T cached = JSONUtil.toBean(cacheValue, type);
            if (cached != null && cacheValueValidator.test(id, cached)) {
                metrics.recordCache(resource, LocalDealsMetrics.CacheResult.HIT);
                return cached;
            }
        } catch (RuntimeException ignored) {
            // A malformed payload is repaired from the database below.
        }
        metrics.recordCache(resource, LocalDealsMetrics.CacheResult.BAD_VALUE);
        return loadObject(resource, cacheKey, id, dbFallback, false);
    }

    public <T> List<T> queryListWithPassThrough(
            LocalDealsMetrics.CacheResource resource,
            String cacheKey,
            Class<T> elementType,
            Supplier<List<T>> dbFallback,
            Function<T, Long> idExtractor) {
        String cacheValue;
        try {
            cacheValue = stringRedisTemplate.opsForValue().get(cacheKey);
        } catch (RuntimeException redisFailure) {
            metrics.recordCache(resource, LocalDealsMetrics.CacheResult.REDIS_ERROR);
            return loadList(resource, cacheKey, dbFallback, true);
        }

        if (cacheValue == null) {
            metrics.recordCache(resource, LocalDealsMetrics.CacheResult.MISS);
            return loadList(resource, cacheKey, dbFallback, false);
        }
        try {
            List<T> cached = new ArrayList<>(JSONUtil.parseArray(cacheValue).toList(elementType));
            if (cached.isEmpty()) {
                metrics.recordCache(resource, LocalDealsMetrics.CacheResult.EMPTY_HIT);
                return cached;
            }
            if (hasValidUniqueIds(cached, idExtractor)) {
                metrics.recordCache(resource, LocalDealsMetrics.CacheResult.HIT);
                return cached;
            }
        } catch (RuntimeException ignored) {
            // A malformed payload is repaired from the database below.
        }
        metrics.recordCache(resource, LocalDealsMetrics.CacheResult.BAD_VALUE);
        return loadList(resource, cacheKey, dbFallback, false);
    }

    private <T, ID> T loadObject(LocalDealsMetrics.CacheResource resource,
                                 String cacheKey,
                                 ID id,
                                 Function<ID, T> dbFallback,
                                 boolean skipCacheWrite) {
        return singleFlightLoader.load(resource, cacheKey, () -> {
            T result = localReadBulkhead.executeDbRead(
                    () -> timedDatabaseLoad(resource, () -> dbFallback.apply(id)));
            if (result == null) {
                bestEffortWrite(resource, cacheKey, EMPTY_PLACEHOLDER,
                        emptyTtl(resource), skipCacheWrite);
                return null;
            }
            bestEffortWrite(resource, cacheKey, JSONUtil.toJsonStr(result),
                    positiveTtl(resource), skipCacheWrite);
            return result;
        });
    }

    private <T> List<T> loadList(LocalDealsMetrics.CacheResource resource,
                                 String cacheKey,
                                 Supplier<List<T>> dbFallback,
                                 boolean skipCacheWrite) {
        return singleFlightLoader.load(resource, cacheKey, () -> {
            List<T> loaded = localReadBulkhead.executeDbRead(
                    () -> timedDatabaseLoad(resource, dbFallback));
            List<T> result = loaded == null ? Collections.emptyList() : loaded;
            boolean empty = result.isEmpty();
            bestEffortWrite(resource, cacheKey, empty ? "[]" : JSONUtil.toJsonStr(result),
                    empty ? emptyTtl(resource) : positiveTtl(resource), skipCacheWrite);
            return result;
        });
    }

    private <T> T timedDatabaseLoad(LocalDealsMetrics.CacheResource resource,
                                    Supplier<T> dbFallback) {
        long startedAt = System.nanoTime();
        try {
            T result = dbFallback.get();
            boolean empty = result == null || result instanceof List && ((List<?>) result).isEmpty();
            metrics.recordCache(resource, empty
                    ? LocalDealsMetrics.CacheResult.DB_EMPTY
                    : LocalDealsMetrics.CacheResult.DB_SUCCESS);
            return result;
        } catch (RuntimeException databaseFailure) {
            metrics.recordCache(resource, LocalDealsMetrics.CacheResult.DB_ERROR);
            throw databaseFailure;
        } finally {
            metrics.recordCacheDbFallback(resource, System.nanoTime() - startedAt);
        }
    }

    private void bestEffortWrite(LocalDealsMetrics.CacheResource resource,
                                 String cacheKey,
                                 String payload,
                                 Duration ttl,
                                 boolean skipCacheWrite) {
        if (skipCacheWrite) {
            metrics.recordCacheMaintenance(resource,
                    LocalDealsMetrics.CacheMaintenanceOperation.WRITE,
                    LocalDealsMetrics.CacheMaintenanceResult.SKIPPED);
            return;
        }
        try {
            stringRedisTemplate.opsForValue().set(
                    cacheKey, payload, ttl.toMillis(), TimeUnit.MILLISECONDS);
            metrics.recordCacheMaintenance(resource,
                    LocalDealsMetrics.CacheMaintenanceOperation.WRITE,
                    LocalDealsMetrics.CacheMaintenanceResult.SUCCESS);
        } catch (RuntimeException redisFailure) {
            metrics.recordCacheMaintenance(resource,
                    LocalDealsMetrics.CacheMaintenanceOperation.WRITE,
                    LocalDealsMetrics.CacheMaintenanceResult.FAILURE);
            warnWriteFailure(resource, redisFailure);
        }
    }

    private void warnWriteFailure(LocalDealsMetrics.CacheResource resource,
                                  RuntimeException redisFailure) {
        long now = System.nanoTime();
        long previous = lastWriteWarningAt.get();
        if ((previous == 0L || now - previous >= WRITE_WARNING_INTERVAL_NANOS)
                && lastWriteWarningAt.compareAndSet(previous, now)) {
            log.warn("Best-effort cache write failed for resource {}", resource, redisFailure);
        }
    }

    private Duration positiveTtl(LocalDealsMetrics.CacheResource resource) {
        return resource == LocalDealsMetrics.CacheResource.SHOP_DETAIL
                ? properties.getShopDetailTtl() : properties.getShopTypeTtl();
    }

    private Duration emptyTtl(LocalDealsMetrics.CacheResource resource) {
        return resource == LocalDealsMetrics.CacheResource.SHOP_DETAIL
                ? properties.getShopDetailEmptyTtl() : properties.getShopTypeEmptyTtl();
    }

    private static <T> boolean hasValidUniqueIds(List<T> values, Function<T, Long> idExtractor) {
        Set<Long> ids = new HashSet<>();
        for (T value : values) {
            if (value == null) {
                return false;
            }
            Long id = idExtractor.apply(value);
            if (id == null || id <= 0L || !ids.add(id)) {
                return false;
            }
        }
        return true;
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
