package com.localdeals.service.impl;

import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.localdeals.dto.Result;
import com.localdeals.entity.ShopType;
import com.localdeals.mapper.ShopTypeMapper;
import com.localdeals.observability.LocalDealsMetrics;
import com.localdeals.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static com.localdeals.utils.RedisConstants.CACHE_SHOP_TYPE_LIST_KEY;
import static com.localdeals.utils.RedisConstants.CACHE_SHOP_TYPE_LIST_TTL;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {
    private final StringRedisTemplate stringRedisTemplate;
    private final LocalDealsMetrics metrics;

    public ShopTypeServiceImpl(StringRedisTemplate stringRedisTemplate, LocalDealsMetrics metrics) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.metrics = metrics;
    }

    @Override
    public List<ShopType> queryTypeList(){
        final String cachJson;
        try {
            cachJson = stringRedisTemplate.opsForValue().get(CACHE_SHOP_TYPE_LIST_KEY);
        } catch (RuntimeException redisFailure) {
            metrics.recordCache(LocalDealsMetrics.CacheResource.SHOP_TYPE,
                    LocalDealsMetrics.CacheResult.REDIS_ERROR);
            throw redisFailure;
        }

        if(StrUtil.isNotBlank(cachJson)){
            metrics.recordCache(LocalDealsMetrics.CacheResource.SHOP_TYPE,
                    LocalDealsMetrics.CacheResult.HIT);
            List<ShopType> typeList = JSONUtil.toList(cachJson, ShopType.class);
            return typeList;
        }
        metrics.recordCache(LocalDealsMetrics.CacheResource.SHOP_TYPE,
                LocalDealsMetrics.CacheResult.MISS);
        long startedAt = System.nanoTime();
        final List<ShopType> typeList;
        try {
            typeList = query().orderByAsc("sort").list();
            metrics.recordCache(LocalDealsMetrics.CacheResource.SHOP_TYPE,
                    CollectionUtil.isEmpty(typeList)
                            ? LocalDealsMetrics.CacheResult.DB_EMPTY
                            : LocalDealsMetrics.CacheResult.DB_SUCCESS);
        } catch (RuntimeException databaseFailure) {
            metrics.recordCache(LocalDealsMetrics.CacheResource.SHOP_TYPE,
                    LocalDealsMetrics.CacheResult.DB_ERROR);
            throw databaseFailure;
        } finally {
            metrics.recordCacheDbFallback(LocalDealsMetrics.CacheResource.SHOP_TYPE,
                    System.nanoTime() - startedAt);
        }

        if(CollectionUtil.isNotEmpty(typeList)){
            try {
                stringRedisTemplate.opsForValue().set(
                        CACHE_SHOP_TYPE_LIST_KEY,
                        JSONUtil.toJsonStr(typeList),
                        CACHE_SHOP_TYPE_LIST_TTL,
                        TimeUnit.MINUTES);
            } catch (RuntimeException redisFailure) {
                metrics.recordCache(LocalDealsMetrics.CacheResource.SHOP_TYPE,
                        LocalDealsMetrics.CacheResult.REDIS_ERROR);
                throw redisFailure;
            }
            return typeList;
        }
        return typeList;
    }
}
