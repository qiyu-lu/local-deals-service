package com.localdeals.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.localdeals.dto.Result;
import com.localdeals.dto.ShopDoc;
import com.localdeals.entity.Shop;
import com.localdeals.mapper.ShopMapper;
import com.localdeals.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.localdeals.utils.CacheClient;
import com.localdeals.utils.RedisData;
import com.localdeals.utils.ShopBloomFilter;
import com.localdeals.utils.SystemConstants;
import org.elasticsearch.common.unit.DistanceUnit;
import org.elasticsearch.index.query.QueryBuilders;
import org.elasticsearch.search.sort.SortBuilders;
import org.elasticsearch.search.sort.SortOrder;
import org.springframework.beans.factory.annotation.Autowired;
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
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;

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
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private ShopBloomFilter shopBloomFilter;

    @Autowired
    private CacheClient cacheClient;

    @Autowired
    private ElasticsearchRestTemplate esRestTemplate;

    @Override
    public Result queryShopById(Long id) {

        // 1️⃣ 布隆过滤器
        if (!shopBloomFilter.mightContain(id)) {
            return Result.fail("店铺不存在");
        }
        Shop shop = cacheClient.queryWithPassThrough(CACHE_SHOP_KEY, id, Shop.class, this::getById, CACHE_SHOP_TTL, TimeUnit.SECONDS);


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
    @Transactional
    public Result updateShop(Shop shop){
        //根据id修改店铺时，先修改数据库，再删除缓存
        Long shopId = shop.getId();
        if(shopId == null){
            return Result.fail("店铺id不能为空");
        }
        updateById(shop);
        stringRedisTemplate.delete(CACHE_SHOP_KEY + shop.getId());
        return Result.ok();
    }

    @Override
    public Result queryShopByType(Integer typeId, Integer current, Double x, Double y) {
        //判断是否需要根据坐标进行查询
        if(x==null || y==null){
            //不需要根据坐标进行查询
            Page<Shop> page = query()
                    .eq("type_id", typeId)
                    .page(new Page<>(current, SystemConstants.DEFAULT_PAGE_SIZE));
            return Result.ok(page.getRecords());
        }
        //计算分页参数，
        int from = (current - 1) * SystemConstants.DEFAULT_PAGE_SIZE;
        int end = current *  SystemConstants.DEFAULT_PAGE_SIZE;

        //查询redis，按照距离排序、分页，结果 ： shopId， 距离
        String key = SHOP_GEO_KEY + typeId;
        GeoResults<RedisGeoCommands.GeoLocation<String>> results = stringRedisTemplate.opsForGeo()
                .search(
                        key,
                        GeoReference.fromCoordinate(x, y),
                        new Distance(5000),
                        RedisGeoCommands.GeoSearchCommandArgs
                                .newGeoSearchArgs().includeDistance().limit(end)
                );
        //解析出id
        if(results == null) {
            return Result.ok(Collections.emptyList());
        }
        List<GeoResult<RedisGeoCommands.GeoLocation<String>>> list = results.getContent();
        if(list.size() <= from) {
            return Result.ok(Collections.emptyList());
        }

        //获取 from到end  的部分
        List<Long> ids = new ArrayList<>(list.size());
        Map<String, Distance> distanceMap = new HashMap<>(list.size());

        list.stream().skip(from).forEach(
                result -> {
                    //获取店铺id
                    String shopIdStr = result.getContent().getName();
                    ids.add(Long.parseLong(shopIdStr));
                    //获取距离
                    Distance distance = result.getDistance();
                    distanceMap.put(shopIdStr, distance);
                }
        );
        //根据id查询shop

        //从blog Service中粘贴过来的：
//        String idStr = StrUtil.join("," , ids);
//        List<Blog> blogs = query().in("id", ids)
//                .last("ORDER BY FIELD(id, " + idStr + ")").list();
        String idStr = StrUtil.join("," , ids);
        List<Shop> shops = query().in("id", ids)
                .last("ORDER BY FIELD(id, " + idStr + ")").list();
        for(Shop shop : shops){
            shop.setDistance(distanceMap.get(shop.getId().toString()).getValue());
        }
        return Result.ok(shops);
    }

    @Override
    public Result searchShops(String keyword, Double x, Double y, Integer radius, Long typeId, Integer current) {
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

        List<ShopDoc> docs = hits.getSearchHits().stream()
                .map(SearchHit::getContent)
                .collect(Collectors.toList());

        return Result.ok(docs);
    }
}
