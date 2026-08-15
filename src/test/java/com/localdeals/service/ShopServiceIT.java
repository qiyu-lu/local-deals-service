package com.localdeals.service;

import com.localdeals.dto.Result;
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
 * 覆盖两条核心路径：缓存缺失与空值缓存。
 */
@SpringBootTest
@ActiveProfiles("test")
class ShopServiceIT {

    @Autowired
    private IShopService shopService;

    @Autowired
    private StringRedisTemplate redisTemplate;

    // shop id=1 在 DB 中存在
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
     * 空值缓存路径：不存在的 id 首次回源 DB 后写入短 TTL 占位符。
     * 进程内 BloomFilter 会在多实例新增商铺时产生错误否定，因此不再作为权威判断。
     */
    @Test
    void queryShopById_unknownId_populatesNullCache() {
        Result result = shopService.queryShopById(UNKNOWN_SHOP_ID);

        assertFalse(result.getSuccess(), "不存在的 ID 应返回失败结果");
        assertTrue(Boolean.TRUE.equals(redisTemplate.hasKey(CACHE_SHOP_KEY + UNKNOWN_SHOP_ID)),
                "不存在的商铺应写入短 TTL 空值缓存，避免重复穿透数据库");
    }

}
