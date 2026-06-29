package com.localdeals.utils;

import cn.hutool.json.JSONUtil;
import com.localdeals.entity.Shop;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.localdeals.utils.RedisConstants.LOCK_SHOP_KEY;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for CacheClient.queryWithLogicalExpire.
 * Tests run against the real Redis instance (localhost:6379 via Docker port-mapping).
 * These tests demonstrate bugs that exist before fixes B3 and B4 are applied.
 */
@SpringBootTest
@ActiveProfiles("test")
class CacheClientIT {

    @Resource
    private CacheClient cacheClient;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    private static final String TEST_KEY_PREFIX = "test:entity:";
    private static final Long TEST_ID = 99999L;

    @AfterEach
    void cleanup() {
        stringRedisTemplate.delete(TEST_KEY_PREFIX + TEST_ID);
        stringRedisTemplate.delete("lock:" + TEST_KEY_PREFIX + TEST_ID);
        stringRedisTemplate.delete(LOCK_SHOP_KEY + TEST_ID);
    }

    /**
     * Bug B3: queryWithLogicalExpire NPEs when the cache key is absent.
     * stringRedisTemplate.get() returns null → JSONUtil.toBean(null, ...) → redisData.getData() throws NPE.
     * After fix: blank/null cacheValue returns null cleanly.
     */
    @Test
    void queryWithLogicalExpire_returnsNull_whenCacheIsEmpty() {
        stringRedisTemplate.delete(TEST_KEY_PREFIX + TEST_ID);

        Shop result = cacheClient.queryWithLogicalExpire(
                TEST_KEY_PREFIX, TEST_ID, Shop.class,
                id -> new Shop().setId(id).setName("db-fallback"),
                10L, TimeUnit.MINUTES
        );

        assertNull(result, "Cache miss must return null without NPE");
    }

    /**
     * Bug B4: queryWithLogicalExpire uses hardcoded LOCK_SHOP_KEY instead of the keyPrefix argument.
     * When called with a non-shop prefix, the lock key lands in the wrong namespace.
     * After fix: lock key is "lock:" + keyPrefix + id.
     */
    @Test
    void queryWithLogicalExpire_lockKey_usesKeyPrefix_notHardcodedShopKey() throws InterruptedException {
        String wrongLockKey = LOCK_SHOP_KEY + TEST_ID;              // "lock:shop:99999" (bug)
        String correctLockKey = "lock:" + TEST_KEY_PREFIX + TEST_ID; // "lock:test:entity:99999"

        // Seed an expired cache entry to trigger the rebuild path
        RedisData data = new RedisData();
        data.setData(new Shop().setId(TEST_ID).setName("stale"));
        data.setExpireTime(LocalDateTime.now().minusMinutes(10));
        stringRedisTemplate.opsForValue().set(
                TEST_KEY_PREFIX + TEST_ID, JSONUtil.toJsonStr(data), 5, TimeUnit.MINUTES);

        stringRedisTemplate.delete(wrongLockKey);
        stringRedisTemplate.delete(correctLockKey);

        // Slow fallback so the lock stays in Redis long enough to observe
        CountDownLatch rebuildStarted = new CountDownLatch(1);
        Function<Long, Shop> slowFallback = id -> {
            rebuildStarted.countDown();
            try { Thread.sleep(600); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
            return new Shop().setId(id);
        };

        // Returns stale data immediately; submits background rebuild that holds the lock
        cacheClient.queryWithLogicalExpire(
                TEST_KEY_PREFIX, TEST_ID, Shop.class, slowFallback, 1L, TimeUnit.MINUTES);

        assertTrue(rebuildStarted.await(3, TimeUnit.SECONDS),
                "Background rebuild must start within 3 seconds");
        Thread.sleep(50); // brief pause so setIfAbsent has completed

        assertFalse(Boolean.TRUE.equals(stringRedisTemplate.hasKey(wrongLockKey)),
                "Must NOT use hardcoded LOCK_SHOP_KEY (lock:shop:)");
        assertTrue(Boolean.TRUE.equals(stringRedisTemplate.hasKey(correctLockKey)),
                "Must use keyPrefix-derived lock key: " + correctLockKey);

        Thread.sleep(700); // wait for rebuild to complete and release lock
    }
}
