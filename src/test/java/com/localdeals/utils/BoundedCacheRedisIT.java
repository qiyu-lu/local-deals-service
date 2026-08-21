package com.localdeals.utils;

import com.localdeals.config.BoundedCacheProperties;
import com.localdeals.config.TrafficControlProperties;
import com.localdeals.entity.Shop;
import com.localdeals.entity.ShopType;
import com.localdeals.observability.LocalDealsMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.connection.DataType;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = {RedisAutoConfiguration.class, BoundedCacheRedisIT.Config.class})
@EnabledIfEnvironmentVariable(named = "M5B_ISOLATED", matches = "true")
class BoundedCacheRedisIT {

    private static final String RUN_ID = requiredEnv("M5B_RUN_ID");
    private static final String PREFIX = "m5b:test:" + RUN_ID + ":";

    @Autowired
    private CacheClient cacheClient;
    @Autowired
    private StringRedisTemplate redisTemplate;
    @Autowired
    private MeterRegistry meterRegistry;

    private final List<String> cleanupKeys = new ArrayList<>();

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.redis.host", () -> requiredEnv("M5B_REDIS_HOST"));
        registry.add("spring.redis.port", () -> requiredEnv("M5B_REDIS_PORT"));
        registry.add("spring.redis.password", () -> requiredEnv("M5B_REDIS_PASSWORD"));
        registry.add("spring.redis.timeout", () -> "500ms");
        registry.add("spring.redis.lettuce.pool.max-wait", () -> "500ms");
        registry.add("local-deals.cache.shop-detail-ttl", () -> "30s");
        registry.add("local-deals.cache.shop-detail-empty-ttl", () -> "5s");
        registry.add("local-deals.cache.shop-type-ttl", () -> "100m");
        registry.add("local-deals.cache.shop-type-empty-ttl", () -> "5s");
    }

    @BeforeEach
    void verifyIsolationSentinel() {
        assertThat(redisTemplate.opsForValue().get("m5b:sentinel:" + RUN_ID))
                .as("Redis must belong to the declared M5B run")
                .isEqualTo(RUN_ID);
    }

    @AfterEach
    void cleanupExactKeys() {
        cleanupKeys.forEach(redisTemplate::delete);
    }

    @Test
    void writesPositiveAndEmptyPayloadsWithTheirConfiguredTtls() {
        String positiveKey = shopKey("ttl-positive", 101L);
        Shop truth = new Shop().setId(101L).setName("truth");

        assertThat(queryShopResult(positiveKey, 101L, truth)).isSameAs(truth);
        assertTtlWithin(positiveKey, 30_000L);

        String emptyKey = shopKey("ttl-empty", 102L);
        assertThat(queryShopResult(emptyKey, 102L, null)).isNull();
        assertThat(redisTemplate.opsForValue().get(emptyKey)).isEqualTo("_NULL_PLACEHOLDER_");
        assertTtlWithin(emptyKey, 5_000L);

        String listKey = key("ttl-empty-list");
        List<ShopType> result = cacheClient.queryListWithPassThrough(
                LocalDealsMetrics.CacheResource.SHOP_TYPE, listKey, ShopType.class,
                Collections::emptyList, ShopType::getId);
        assertThat(result).isEmpty();
        assertThat(redisTemplate.opsForValue().get(listKey)).isEqualTo("[]");
        assertTtlWithin(listKey, 5_000L);
    }

    @Test
    void repairsMalformedAndMismatchedValuesFromDatabaseTruth() {
        String malformedKey = shopKey("bad-json", 201L);
        redisTemplate.opsForValue().set(malformedKey, "{bad-json", 1, TimeUnit.MINUTES);
        AtomicInteger malformedCalls = new AtomicInteger();
        Shop truth = new Shop().setId(201L).setName("repaired");

        assertThat(queryShop(malformedKey, 201L, () -> {
            malformedCalls.incrementAndGet();
            return truth;
        })).isSameAs(truth);
        assertThat(queryShop(malformedKey, 201L, () -> {
            malformedCalls.incrementAndGet();
            return truth;
        }).getName()).isEqualTo("repaired");
        assertThat(malformedCalls).hasValue(1);

        String mismatchKey = shopKey("bad-id", 202L);
        redisTemplate.opsForValue().set(mismatchKey,
                "{\"id\":999,\"name\":\"wrong\"}", 1, TimeUnit.MINUTES);
        Shop corrected = new Shop().setId(202L).setName("correct");
        assertThat(queryShopResult(mismatchKey, 202L, corrected)).isSameAs(corrected);
        assertThat(redisTemplate.opsForValue().get(mismatchKey)).contains("\"id\":202");
    }

    @Test
    void wrongRedisTypeFallsBackWithoutAttemptingASecondRedisCommand() {
        String wrongTypeKey = shopKey("wrong-type", 301L);
        redisTemplate.opsForList().rightPush(wrongTypeKey, "not-a-string");
        Shop truth = new Shop().setId(301L).setName("database");

        assertThat(queryShopResult(wrongTypeKey, 301L, truth)).isSameAs(truth);
        assertThat(redisTemplate.type(wrongTypeKey)).isEqualTo(DataType.LIST);
    }

    @Test
    void thirtyTwoConcurrentMissesShareOneRealDatabaseCallback() throws Exception {
        String cacheKey = shopKey("singleflight", 401L);
        ExecutorService executor = Executors.newFixedThreadPool(32);
        CountDownLatch ready = new CountDownLatch(32);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch callbackEntered = new CountDownLatch(1);
        CountDownLatch releaseCallback = new CountDownLatch(1);
        AtomicInteger databaseCalls = new AtomicInteger();
        List<Future<Shop>> futures = new ArrayList<>();
        double sharedBefore = singleFlightCount("shared");
        try {
            for (int index = 0; index < 32; index++) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    return queryShop(cacheKey, 401L, () -> {
                        databaseCalls.incrementAndGet();
                        callbackEntered.countDown();
                        releaseCallback.await();
                        return new Shop().setId(401L).setName("shared");
                    });
                }));
            }
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            assertThat(callbackEntered.await(5, TimeUnit.SECONDS)).isTrue();
            awaitSharedDelta(sharedBefore, 31D);
            releaseCallback.countDown();
            for (Future<Shop> future : futures) {
                assertThat(future.get(5, TimeUnit.SECONDS).getId()).isEqualTo(401L);
            }
            assertThat(databaseCalls).hasValue(1);
        } finally {
            releaseCallback.countDown();
            executor.shutdownNow();
        }
    }

    private Shop queryShopResult(String exactKey, Long id, Shop result) {
        return queryShop(exactKey, id, () -> result);
    }

    private Shop queryShop(String exactKey, Long id, CheckedSupplier<Shop> fallback) {
        String keyPrefix = exactKey.substring(0, exactKey.length() - id.toString().length());
        return cacheClient.queryWithPassThrough(
                LocalDealsMetrics.CacheResource.SHOP_DETAIL,
                keyPrefix,
                id,
                Shop.class,
                ignored -> {
                    try {
                        return fallback.get();
                    } catch (RuntimeException failure) {
                        throw failure;
                    } catch (Exception failure) {
                        throw new IllegalStateException(failure);
                    }
                },
                (requestedId, cached) -> requestedId.equals(cached.getId()));
    }

    private String key(String suffix) {
        String key = PREFIX + suffix + ":";
        cleanupKeys.add(key);
        return key;
    }

    private String shopKey(String suffix, Long id) {
        String key = PREFIX + suffix + ":" + id;
        cleanupKeys.add(key);
        return key;
    }

    private void assertTtlWithin(String key, long configuredMillis) {
        Long ttl = redisTemplate.getExpire(key, TimeUnit.MILLISECONDS);
        assertThat(ttl).isNotNull().isPositive().isLessThanOrEqualTo(configuredMillis);
        assertThat(ttl).isGreaterThan(configuredMillis - 2_000L);
    }

    private double singleFlightCount(String result) {
        return meterRegistry.get("local_deals.cache.singleflight")
                .tags("resource", "shop_detail", "result", result).counter().count();
    }

    private void awaitSharedDelta(double before, double expectedDelta) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (singleFlightCount("shared") - before != expectedDelta
                && System.nanoTime() < deadline) {
            Thread.yield();
        }
        assertThat(singleFlightCount("shared") - before).isEqualTo(expectedDelta);
    }

    private static String requiredEnv(String name) {
        String value = System.getenv(name);
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalStateException(name + " must be set for isolated M5B IT");
        }
        return value;
    }

    @FunctionalInterface
    private interface CheckedSupplier<T> {
        T get() throws Exception;
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties({BoundedCacheProperties.class, TrafficControlProperties.class})
    @Import({CacheClient.class, SingleFlightLoader.class, com.localdeals.service.LocalReadBulkhead.class})
    static class Config {
        @Bean
        MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }

        @Bean
        LocalDealsMetrics localDealsMetrics(MeterRegistry registry) {
            return new LocalDealsMetrics(registry);
        }
    }
}
