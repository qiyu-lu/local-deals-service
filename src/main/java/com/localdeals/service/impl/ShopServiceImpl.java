package com.localdeals.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.localdeals.dto.Result;
import com.localdeals.dto.ShopDoc;
import com.localdeals.entity.Shop;
import com.localdeals.mapper.ShopMapper;
import com.localdeals.observability.LocalDealsMetrics;
import com.localdeals.service.IShopService;
import com.localdeals.service.LocalReadBulkhead;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.localdeals.utils.CacheClient;
import com.localdeals.utils.RedisData;
import com.localdeals.utils.SystemConstants;
import org.elasticsearch.common.unit.DistanceUnit;
import org.elasticsearch.index.query.QueryBuilders;
import org.elasticsearch.search.sort.SortBuilders;
import org.elasticsearch.search.sort.SortOrder;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.elasticsearch.core.ElasticsearchRestTemplate;
import org.springframework.data.elasticsearch.core.SearchHit;
import org.springframework.data.elasticsearch.core.SearchHits;
import org.springframework.data.elasticsearch.core.mapping.IndexCoordinates;
import org.springframework.data.elasticsearch.core.query.NativeSearchQueryBuilder;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResult;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.domain.geo.GeoReference;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static com.localdeals.utils.RedisConstants.*;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {
    private final StringRedisTemplate stringRedisTemplate;
    private final CacheClient cacheClient;
    private final ElasticsearchRestTemplate esRestTemplate;
    private final LocalReadBulkhead localReadBulkhead;

    public ShopServiceImpl(StringRedisTemplate stringRedisTemplate,
                           CacheClient cacheClient,
                           ElasticsearchRestTemplate esRestTemplate,
                           LocalReadBulkhead localReadBulkhead) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.cacheClient = cacheClient;
        this.esRestTemplate = esRestTemplate;
        this.localReadBulkhead = localReadBulkhead;
    }

    @Override
    public Result queryShopById(Long id) {

        Shop shop = cacheClient.queryWithPassThrough(
                LocalDealsMetrics.CacheResource.SHOP_DETAIL,
                CACHE_SHOP_KEY,
                id,
                Shop.class,
                this::getById,
                (requestedId, cachedShop) -> requestedId.equals(cachedShop.getId()));


        // 其他策略（教学用，已注释）：
        // Shop shop = queryWithMutex(id);  // 互斥锁方案（见本类 queryWithMutex 方法）
        // Shop shop = cacheClient.queryWithLogicalExpire(CACHE_SHOP_KEY, id, Shop.class, this::getById, CACHE_SHOP_TTL, TimeUnit.SECONDS);  // 逻辑过期方案

        if (shop == null) {
            return Result.fail("店铺不存在");
        }

        return Result.ok(shop);
    }


    public void saveShop2RedisCache(Long id, Long expiredSeconds) {
        // 1️⃣ 查数据库
        Shop shop = getById(id);
        // 2️⃣ 构造 RedisData
        RedisData redisData = new RedisData();
        redisData.setData(shop);
        redisData.setExpireTime(
                LocalDateTime.now().plusSeconds(expiredSeconds)
        );

        // 3️⃣ 写入 Redis（不设置 TTL）
        stringRedisTemplate.opsForValue().set(
                CACHE_SHOP_KEY + id,
                JSONUtil.toJsonStr(redisData)
        );
    }

    private Shop queryWithMutex(Long id) {

        String shopKey = CACHE_SHOP_KEY + id;
        String lockKey = LOCK_SHOP_KEY + id;
        boolean locked = false;

        try {
            while (true) {
                // 1️⃣ 查缓存
                String shopJson = stringRedisTemplate.opsForValue().get(shopKey);
                if (StrUtil.isNotBlank(shopJson)) {
                    return "null".equals(shopJson) ? null : JSONUtil.toBean(shopJson, Shop.class);
                }

                // 2️⃣ 尝试获取锁
                locked = Boolean.TRUE.equals(
                        stringRedisTemplate.opsForValue()
                                .setIfAbsent(lockKey, "1", LOCK_SHOP_TTL, TimeUnit.SECONDS)
                );
                if (!locked) {
                    Thread.sleep(50); // 自旋等待后重试
                    continue;
                }

                // 3️⃣ 获锁成功，再次检查缓存（double-check）
                shopJson = stringRedisTemplate.opsForValue().get(shopKey);
                if (StrUtil.isNotBlank(shopJson)) {
                    return "null".equals(shopJson) ? null : JSONUtil.toBean(shopJson, Shop.class);
                }

                // 4️⃣ 查数据库
                Shop shop = getById(id);
                if (shop == null) {
                    stringRedisTemplate.opsForValue()
                            .set(shopKey, "null", CACHE_NULL_TTL, TimeUnit.MINUTES);
                    return null;
                }

                // 5️⃣ 写缓存
                stringRedisTemplate.opsForValue()
                        .set(shopKey, JSONUtil.toJsonStr(shop), CACHE_SHOP_TTL, TimeUnit.MINUTES);
                return shop;
            }
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            if (locked) {
                stringRedisTemplate.delete(lockKey);
            }
        }
    }


    @Override
    public Result queryShopByType(Integer typeId, Integer current, Double x, Double y, String sortBy) {
        // comments / score sort: handled directly by DB, no geo needed
        if ("comments".equals(sortBy) || "score".equals(sortBy)) {
            Page<Shop> page = query()
                    .eq("type_id", typeId)
                    .orderByDesc(sortBy)
                    .page(new Page<>(current, SystemConstants.DEFAULT_PAGE_SIZE));
            return Result.ok(page.getRecords());
        }

        // distance sort or default: requires Redis GEO
        if (x == null || y == null) {
            Page<Shop> page = query()
                    .eq("type_id", typeId)
                    .page(new Page<>(current, SystemConstants.DEFAULT_PAGE_SIZE));
            return Result.ok(page.getRecords());
        }

        int from = (current - 1) * SystemConstants.DEFAULT_PAGE_SIZE;
        int end = current * SystemConstants.DEFAULT_PAGE_SIZE;

        String key = SHOP_GEO_KEY + typeId;
        GeoResults<RedisGeoCommands.GeoLocation<String>> results = stringRedisTemplate.opsForGeo()
                .search(
                        key,
                        GeoReference.fromCoordinate(x, y),
                        new Distance(5000),
                        RedisGeoCommands.GeoSearchCommandArgs
                                .newGeoSearchArgs().includeDistance().limit(end)
                );
        // Redis GEO key not loaded (e.g., after Redis restart) — fall back to DB
        if (results == null || results.getContent().isEmpty()) {
            Page<Shop> page = query()
                    .eq("type_id", typeId)
                    .page(new Page<>(current, SystemConstants.DEFAULT_PAGE_SIZE));
            return Result.ok(page.getRecords());
        }

        List<GeoResult<RedisGeoCommands.GeoLocation<String>>> list = results.getContent();
        if (list.size() <= from) {
            return Result.ok(Collections.emptyList());
        }

        List<Long> ids = new ArrayList<>(list.size());
        Map<String, Distance> distanceMap = new HashMap<>(list.size());
        list.stream().skip(from).forEach(result -> {
            String shopIdStr = result.getContent().getName();
            ids.add(Long.parseLong(shopIdStr));
            distanceMap.put(shopIdStr, result.getDistance());
        });

        String idStr = StrUtil.join(",", ids);
        List<Shop> shops = query().in("id", ids)
                .last("ORDER BY FIELD(id, " + idStr + ")").list();
        for (Shop shop : shops) {
            shop.setDistance(distanceMap.get(shop.getId().toString()).getValue());
        }
        return Result.ok(shops);
    }

    @Override
    public Result queryShopByName(String name, Integer current) {
        requirePositivePage(current);
        return localReadBulkhead.executeSearch(() -> {
            Page<Shop> page = query()
                    .like(StrUtil.isNotBlank(name), "name", name)
                    .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
            return Result.ok(page.getRecords());
        });
    }

    @Override
    public Result searchShops(String keyword, Double x, Double y, Integer radius, Long typeId, Integer current) {
        requirePositivePage(current);
        return localReadBulkhead.executeSearch(
                () -> searchShopsAdmitted(keyword, x, y, radius, typeId, current));
    }

    private Result searchShopsAdmitted(String keyword, Double x, Double y,
                                       Integer radius, Long typeId, Integer current) {
        NativeSearchQueryBuilder queryBuilder = new NativeSearchQueryBuilder();

        // 关键词全文检索（name 或 address 包含关键词）
        if (StrUtil.isNotBlank(keyword)) {
            queryBuilder.withQuery(QueryBuilders.multiMatchQuery(keyword, "name", "address"));
        } else {
            queryBuilder.withQuery(QueryBuilders.matchAllQuery());
        }

        // typeId 过滤
        if (typeId != null) {
            queryBuilder.withFilter(QueryBuilders.termQuery("typeId", typeId));
        }

        // 地理位置过滤 + 距离排序
        if (x != null && y != null) {
            int radiusMeters = radius != null ? radius : 5000;
            queryBuilder.withFilter(
                    QueryBuilders.geoDistanceQuery("location")
                            .point(y, x)
                            .distance(radiusMeters + "m")
            );
            queryBuilder.withSort(
                    SortBuilders.geoDistanceSort("location", y, x)
                            .order(SortOrder.ASC)
                            .unit(DistanceUnit.METERS)
            );
        }

        // 分页
        int pageSize = SystemConstants.DEFAULT_PAGE_SIZE;
        queryBuilder.withPageable(PageRequest.of(current - 1, pageSize));

        SearchHits<ShopDoc> hits = esRestTemplate.search(queryBuilder.build(), ShopDoc.class,
                IndexCoordinates.of("shop_index"));

        List<Long> ids = hits.getSearchHits().stream()
                .map(h -> h.getContent().getId())
                .collect(Collectors.toList());

        if (ids.isEmpty()) {
            return Result.ok(Collections.emptyList());
        }

        // 用 ES 排好序的 ID 回查 DB，保留排序并带上 images 等完整字段
        String idStr = StrUtil.join(",", ids);
        List<Shop> shops = query().in("id", ids)
                .last("ORDER BY FIELD(id, " + idStr + ")").list();

        return Result.ok(shops);
    }

    private static void requirePositivePage(Integer current) {
        if (current == null || current <= 0) {
            throw new IllegalArgumentException("current must be positive");
        }
    }
}
