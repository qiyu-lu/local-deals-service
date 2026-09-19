package com.localdeals.merchant.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.localdeals.platform.dto.Result;
import com.localdeals.merchant.dto.ShopDoc;
import com.localdeals.merchant.entity.Shop;
import com.localdeals.merchant.mapper.ShopMapper;
import com.localdeals.platform.observability.LocalDealsMetrics;
import com.localdeals.merchant.service.IShopService;
import com.localdeals.platform.service.LocalReadBulkhead;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.localdeals.platform.utils.CacheClient;
import com.localdeals.platform.utils.SystemConstants;
import co.elastic.clients.elasticsearch._types.DistanceUnit;
import co.elastic.clients.elasticsearch._types.SortOrder;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.elasticsearch.client.elc.NativeQuery;
import org.springframework.data.elasticsearch.client.elc.NativeQueryBuilder;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.SearchHits;
import org.springframework.data.elasticsearch.core.mapping.IndexCoordinates;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResult;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.domain.geo.GeoReference;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

import static com.localdeals.platform.utils.RedisConstants.*;

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
    private final ElasticsearchOperations elasticsearch;
    private final LocalReadBulkhead localReadBulkhead;

    public ShopServiceImpl(StringRedisTemplate stringRedisTemplate,
                           CacheClient cacheClient,
                           ElasticsearchOperations elasticsearch,
                           LocalReadBulkhead localReadBulkhead) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.cacheClient = cacheClient;
        this.elasticsearch = elasticsearch;
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


        if (shop == null) {
            return Result.fail("店铺不存在");
        }

        return Result.ok(shop);
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
    public Result searchShops(String keyword, Double x, Double y, Integer radius, Long typeId, Integer current) {
        requirePositivePage(current);
        return localReadBulkhead.executeSearch(
                () -> searchShopsAdmitted(keyword, x, y, radius, typeId, current));
    }

    private Result searchShopsAdmitted(String keyword, Double x, Double y,
                                       Integer radius, Long typeId, Integer current) {
        NativeQueryBuilder queryBuilder = NativeQuery.builder();

        // 关键词全文检索（name 或 address 包含关键词）
        if (StrUtil.isNotBlank(keyword)) {
            queryBuilder.withQuery(q -> q.multiMatch(m -> m.query(keyword).fields("name", "address")));
        } else {
            queryBuilder.withQuery(q -> q.matchAll(m -> m));
        }

        // typeId 过滤
        if (typeId != null) {
            queryBuilder.withFilter(q -> q.term(t -> t.field("typeId").value(typeId)));
        }

        // 地理位置过滤 + 距离排序
        if (x != null && y != null) {
            int radiusMeters = radius != null ? radius : 5000;
            queryBuilder.withFilter(q -> q.geoDistance(g -> g.field("location")
                    .location(l -> l.latlon(p -> p.lat(y).lon(x)))
                    .distance(radiusMeters + "m")));
            queryBuilder.withSort(s -> s.geoDistance(g -> g.field("location")
                    .location(l -> l.latlon(p -> p.lat(y).lon(x)))
                    .order(SortOrder.Asc)
                    .unit(DistanceUnit.Meters)));
        }

        // 分页
        int pageSize = SystemConstants.DEFAULT_PAGE_SIZE;
        queryBuilder.withPageable(PageRequest.of(current - 1, pageSize));

        SearchHits<ShopDoc> hits = elasticsearch.search(queryBuilder.build(), ShopDoc.class,
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
