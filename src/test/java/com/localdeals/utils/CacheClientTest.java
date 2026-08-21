package com.localdeals.utils;

import com.localdeals.config.BoundedCacheProperties;
import com.localdeals.config.TrafficControlProperties;
import com.localdeals.entity.Shop;
import com.localdeals.observability.LocalDealsMetrics;
import com.localdeals.service.LocalReadBulkhead;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static com.localdeals.utils.RedisConstants.CACHE_SHOP_KEY;
import static com.localdeals.utils.RedisConstants.EMPTY_PLACEHOLDER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CacheClientTest {

    private StringRedisTemplate redisTemplate;
    private ValueOperations<String, String> valueOperations;
    private SimpleMeterRegistry registry;
    private CacheClient cacheClient;
    private BoundedCacheProperties cacheProperties;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        redisTemplate = mock(StringRedisTemplate.class);
        valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        registry = new SimpleMeterRegistry();
        LocalDealsMetrics metrics = new LocalDealsMetrics(registry);
        cacheProperties = new BoundedCacheProperties();
        cacheProperties.setShopDetailTtl(Duration.ofSeconds(45));
        cacheProperties.setShopDetailEmptyTtl(Duration.ofSeconds(5));
        cacheProperties.validate();
        cacheClient = new CacheClient(redisTemplate, metrics, cacheProperties,
                new SingleFlightLoader(metrics, new TrafficControlProperties()),
                new LocalReadBulkhead(new TrafficControlProperties(), metrics));
    }

    @Test
    void returnsValidatedHitWithoutDatabaseFallback() {
        when(valueOperations.get("cache:shop:1"))
                .thenReturn("{\"id\":1,\"name\":\"cached\"}");
        AtomicInteger databaseCalls = new AtomicInteger();

        Shop result = queryShop(1L, id -> {
            databaseCalls.incrementAndGet();
            return new Shop().setId(id).setName("database");
        });

        assertThat(result.getName()).isEqualTo("cached");
        assertThat(databaseCalls).hasValue(0);
        assertCounter("local_deals.cache.access", "result", "hit", 1D);
    }

    @Test
    void returnsExactEmptyPlaceholderWithoutDatabaseFallback() {
        when(valueOperations.get("cache:shop:2")).thenReturn(EMPTY_PLACEHOLDER);

        Shop result = queryShop(2L, id -> new Shop().setId(id));

        assertThat(result).isNull();
        assertCounter("local_deals.cache.access", "result", "empty_hit", 1D);
        verify(valueOperations, never()).set(anyString(), anyString(), eq(5_000L),
                eq(TimeUnit.MILLISECONDS));
    }

    @Test
    void missUsesPositiveAndNegativeTtls() {
        when(valueOperations.get("cache:shop:3")).thenReturn(null);
        Shop databaseShop = new Shop().setId(3L).setName("database");

        assertThat(queryShop(3L, id -> databaseShop)).isSameAs(databaseShop);
        verify(valueOperations).set(eq("cache:shop:3"), anyString(), eq(45_000L),
                eq(TimeUnit.MILLISECONDS));
        assertMaintenance("success", 1D);

        when(valueOperations.get("cache:shop:4")).thenReturn(null);
        assertThat(queryShop(4L, id -> null)).isNull();
        verify(valueOperations).set("cache:shop:4", EMPTY_PLACEHOLDER, 5_000L,
                TimeUnit.MILLISECONDS);
        assertCounter("local_deals.cache.access", "result", "db_empty", 1D);
    }

    @Test
    void malformedJsonAndMismatchedIdAreBadValuesAndAreRepaired() {
        when(valueOperations.get("cache:shop:5")).thenReturn("{bad-json");
        Shop repaired = new Shop().setId(5L).setName("truth");

        assertThat(queryShop(5L, id -> repaired)).isSameAs(repaired);
        verify(valueOperations).set(eq("cache:shop:5"), anyString(), eq(45_000L),
                eq(TimeUnit.MILLISECONDS));

        when(valueOperations.get("cache:shop:6"))
                .thenReturn("{\"id\":999,\"name\":\"wrong\"}");
        Shop corrected = new Shop().setId(6L).setName("correct");
        assertThat(queryShop(6L, id -> corrected)).isSameAs(corrected);
        verify(valueOperations).set(eq("cache:shop:6"), anyString(), eq(45_000L),
                eq(TimeUnit.MILLISECONDS));
        assertCounter("local_deals.cache.access", "result", "bad_value", 2D);
    }

    @Test
    void redisReadFailureFallsBackAndSkipsWrite() {
        when(valueOperations.get("cache:shop:7"))
                .thenThrow(new IllegalStateException("redis unavailable"));
        Shop databaseShop = new Shop().setId(7L).setName("database");

        assertThat(queryShop(7L, id -> databaseShop)).isSameAs(databaseShop);

        verify(valueOperations, never()).set(eq("cache:shop:7"), anyString(),
                eq(45_000L), eq(TimeUnit.MILLISECONDS));
        assertCounter("local_deals.cache.access", "result", "redis_error", 1D);
        assertMaintenance("skipped", 1D);
    }

    @Test
    void databaseFailureIsPropagatedAndNeverCached() {
        when(valueOperations.get("cache:shop:8")).thenReturn(null);
        RuntimeException databaseFailure = new IllegalStateException("database unavailable");

        assertThatThrownBy(() -> queryShop(8L, id -> {
            throw databaseFailure;
        })).isInstanceOfSatisfying(com.localdeals.exception.ApiStatusException.class, error -> {
            assertThat(error.getStatus().value()).isEqualTo(503);
            assertThat(error.getCode()).isEqualTo(
                    com.localdeals.exception.ApiErrorCodes.DATABASE_UNAVAILABLE);
        });

        verify(valueOperations, never()).set(eq("cache:shop:8"), anyString(),
                eq(45_000L), eq(TimeUnit.MILLISECONDS));
        assertCounter("local_deals.cache.access", "result", "db_error", 1D);
    }

    @Test
    void redisWriteFailureDoesNotReplaceDatabaseTruth() {
        when(valueOperations.get("cache:shop:9")).thenReturn(null);
        doThrow(new IllegalStateException("redis write failed")).when(valueOperations)
                .set(eq("cache:shop:9"), anyString(), eq(45_000L), eq(TimeUnit.MILLISECONDS));
        Shop databaseShop = new Shop().setId(9L).setName("database");

        assertThat(queryShop(9L, id -> databaseShop)).isSameAs(databaseShop);

        assertCounter("local_deals.cache.access", "result", "redis_error", 0D);
        assertMaintenance("failure", 1D);
    }

    @Test
    void timedOutFollowerDoesNotAcquireDbPermitOrStartASecondFallback() throws Exception {
        TrafficControlProperties traffic = new TrafficControlProperties();
        traffic.getRead().setSharedLoadWait(Duration.ofMillis(100));
        traffic.getRead().setDbMaxConcurrent(1);
        traffic.validate();
        LocalDealsMetrics metrics = new LocalDealsMetrics(registry);
        cacheClient = new CacheClient(redisTemplate, metrics, cacheProperties,
                new SingleFlightLoader(metrics, traffic), new LocalReadBulkhead(traffic, metrics));
        when(valueOperations.get("cache:shop:10")).thenReturn(null);
        java.util.concurrent.CountDownLatch fallbackEntered = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch releaseFallback = new java.util.concurrent.CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            java.util.concurrent.Future<Shop> leader = executor.submit(() -> queryShop(10L, id -> {
                calls.incrementAndGet();
                fallbackEntered.countDown();
                try {
                    releaseFallback.await();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException(interrupted);
                }
                return new Shop().setId(id).setName("database");
            }));
            assertThat(fallbackEntered.await(5, TimeUnit.SECONDS)).isTrue();

            assertThatThrownBy(() -> queryShop(10L, id -> {
                calls.incrementAndGet();
                return new Shop().setId(id).setName("unexpected");
            })).isInstanceOfSatisfying(com.localdeals.exception.ApiStatusException.class,
                    error -> assertThat(error.getCode()).isEqualTo(
                            com.localdeals.exception.ApiErrorCodes.DATABASE_UNAVAILABLE));

            assertThat(calls).hasValue(1);
            assertThat(registry.get("local_deals.traffic.decision")
                    .tags("resource", "db_read", "result", "allowed", "reason", "none")
                    .counter().count()).isEqualTo(1D);
            assertThat(registry.get("local_deals.traffic.decision")
                    .tags("resource", "db_read", "result", "rejected", "reason", "concurrency")
                    .counter().count()).isZero();
            releaseFallback.countDown();
            assertThat(leader.get(5, TimeUnit.SECONDS).getName()).isEqualTo("database");
        } finally {
            releaseFallback.countDown();
            executor.shutdownNow();
        }
    }

    private Shop queryShop(Long id, java.util.function.Function<Long, Shop> fallback) {
        return cacheClient.queryWithPassThrough(
                LocalDealsMetrics.CacheResource.SHOP_DETAIL,
                CACHE_SHOP_KEY,
                id,
                Shop.class,
                fallback,
                (requestedId, cached) -> requestedId.equals(cached.getId()));
    }

    private void assertCounter(String meter, String tag, String value, double expected) {
        assertThat(registry.get(meter)
                .tags("resource", "shop_detail", tag, value)
                .counter().count()).isEqualTo(expected);
    }

    private void assertMaintenance(String result, double expected) {
        assertThat(registry.get("local_deals.cache.maintenance")
                .tags("resource", "shop_detail", "operation", "write", "result", result)
                .counter().count()).isEqualTo(expected);
    }
}
