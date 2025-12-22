package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisData;
import com.hmdp.utils.ShopBloomFilter;
import org.apache.tomcat.jni.Time;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;

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
    private static final ExecutorService CACHE_REBUILD_EXECUTOR =
            Executors.newFixedThreadPool(10);

    @Autowired
    private ShopBloomFilter shopBloomFilter;

    @Autowired
    private CacheClient cacheClient;

    @Override
    public Result queryShopById(Long id) {

        // 1️⃣ 布隆过滤器
        if (!shopBloomFilter.mightContain(id)) {
            return Result.fail("店铺不存在");
        }
        Shop shop = cacheClient.queryWithPassThrough(CACHE_SHOP_KEY, id, Shop.class, this::getById, CACHE_SHOP_TTL, TimeUnit.SECONDS);


        // 2️⃣ 选择一种策略
        //Shop shop = queryWithLogicalExpire(id);
        // Shop shop = queryWithMutex(id);

        // Shop shop = cacheClient.queryWithLogicalExpire(CACHE_SHOP_KEY,id,Shop.class, this::getById,CACHE_SHOP_TTL,TimeUnit.SECONDS);

        if (shop == null) {
            return Result.fail("店铺不存在");
        }

        return Result.ok(shop);
    }


    public void saveShop2RedisCache(Long id, Long expiredSeconds) throws InterruptedException {
        // 1️⃣ 查数据库
        Shop shop = getById(id);
        //模拟缓存重建的延迟，
        Thread.sleep(200);
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

        // 1️⃣ 查缓存
        String shopJson = stringRedisTemplate.opsForValue().get(shopKey);
        if (StrUtil.isNotBlank(shopJson)) {
            if ("null".equals(shopJson)) {
                return null;
            }
            return JSONUtil.toBean(shopJson, Shop.class);
        }

        // 2️⃣ 缓存未命中，尝试获取锁
        String lockKey = LOCK_SHOP_KEY + id;
        boolean locked = false;

        try {
            locked = Boolean.TRUE.equals(
                    stringRedisTemplate.opsForValue()
                            .setIfAbsent(lockKey, "1", LOCK_SHOP_TTL, TimeUnit.SECONDS)
            );

            if (!locked) {
                // 简单自旋
                Thread.sleep(50);
                return queryWithMutex(id);
            }

            // 3️⃣ 再次检查缓存
            shopJson = stringRedisTemplate.opsForValue().get(shopKey);
            if (StrUtil.isNotBlank(shopJson)) {
                if ("null".equals(shopJson)) {
                    return null;
                }
                return JSONUtil.toBean(shopJson, Shop.class);
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
                    .set(shopKey, JSONUtil.toJsonStr(shop),
                            CACHE_SHOP_TTL, TimeUnit.MINUTES);

            return shop;

        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            if (locked) {
                stringRedisTemplate.delete(lockKey);
            }
        }
    }
    private Shop queryWithLogicalExpire(Long id) {

        String shopKey = CACHE_SHOP_KEY + id;

        // 1️⃣ 查 Redis
        String shopJson = stringRedisTemplate.opsForValue().get(shopKey);
        if (StrUtil.isBlank(shopJson)) {
            return null;
        }

        // 2️⃣ 反序列化
        RedisData redisData = JSONUtil.toBean(shopJson, RedisData.class);

        if (redisData.getData() == null) {
            return null;
        }

        Shop shop = JSONUtil.toBean(
                (JSONObject) redisData.getData(), Shop.class);

        // 3️⃣ 未过期，直接返回
        if (redisData.getExpireTime().isAfter(LocalDateTime.now())) {
            return shop;
        }

        // 4️⃣ 已过期，尝试重建
        String lockKey = LOCK_SHOP_KEY + id;
        boolean locked = Boolean.TRUE.equals(
                stringRedisTemplate.opsForValue()
                        .setIfAbsent(lockKey, "1", LOCK_SHOP_TTL, TimeUnit.SECONDS)
        );

        if (locked) {
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try {
                    saveShop2RedisCache(id, 20L);
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                } finally {
                    stringRedisTemplate.delete(lockKey);
                }
            });
        }

        // 5️⃣ 返回旧数据
        return shop;
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
}
