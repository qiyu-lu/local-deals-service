package com.localdeals.service;

import com.localdeals.dto.Result;
import com.localdeals.entity.Shop;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;

import static com.localdeals.utils.RedisConstants.CACHE_SHOP_KEY;
import static org.junit.jupiter.api.Assertions.*;

/**
 * ShopServiceImpl 缓存策略集成测试。
 * 覆盖三条核心路径：缓存缺失、布隆过滤器拦截、更新清缓存。
 */
@SpringBootTest
@ActiveProfiles("test")
class ShopServiceIT {

    @Autowired
    private IShopService shopService;

    @Autowired
    private StringRedisTemplate redisTemplate;

    // shop id=1 在 DB 中存在且由 ShopBloomFilter @PostConstruct 加载
    private static final Long EXISTING_SHOP_ID = 1L;
    // 不存在于 DB 的 id，布隆过滤器必定拒绝
    private static final Long UNKNOWN_SHOP_ID = 999999L;

    @BeforeEach
    @AfterEach
    void clearCache() {
        redisTemplate.delete(CACHE_SHOP_KEY + EXISTING_SHOP_ID);
        redisTemplate.delete(CACHE_SHOP_KEY + UNKNOWN_SHOP_ID);
    }

    /**
     * 缓存缺失路径：Redis 无数据 → 查 DB → 写缓存 → 返回店铺。
     * 验证 CacheClient.queryWithPassThrough 的写缓存行为。
     */
    @Test
    void queryShopById_cacheMiss_populatesRedisAndReturnsShop() {
        Result result = shopService.queryShopById(EXISTING_SHOP_ID);

        assertTrue(result.getSuccess(), "已存在的店铺查询应成功");
        assertTrue(Boolean.TRUE.equals(redisTemplate.hasKey(CACHE_SHOP_KEY + EXISTING_SHOP_ID)),
                "缓存缺失后应将查询结果写入 Redis");
    }

    /**
     * 布隆过滤器拦截路径：id 不在布隆过滤器中 → 直接返回失败，不访问 Redis/DB。
     * 验证缓存穿透的第一道防线有效。
     */
    @Test
    void queryShopById_unknownId_bloomFilterRejectsWithoutRedisWrite() {
        Result result = shopService.queryShopById(UNKNOWN_SHOP_ID);

        assertFalse(result.getSuccess(), "不存在的 ID 应返回失败结果");
        assertFalse(Boolean.TRUE.equals(redisTemplate.hasKey(CACHE_SHOP_KEY + UNKNOWN_SHOP_ID)),
                "布隆过滤器拦截后不应向 Redis 写入任何占位符");
    }

    /**
     * 缓存失效路径：updateShop 先更新 DB，再删除 Redis 缓存 key。
     * 验证"先写数据库，再删缓存"策略正确执行。
     */
    @Test
    void updateShop_deletesRedisCache() {
        redisTemplate.opsForValue().set(CACHE_SHOP_KEY + EXISTING_SHOP_ID, "{\"id\":1}");

        Shop shop = shopService.getById(EXISTING_SHOP_ID);
        Result result = shopService.updateShop(shop);

        assertTrue(result.getSuccess(), "updateShop 应返回成功");
        assertFalse(Boolean.TRUE.equals(redisTemplate.hasKey(CACHE_SHOP_KEY + EXISTING_SHOP_ID)),
                "更新店铺后应删除 Redis 缓存 key，触发下次查询时重新加载");
    }
}
