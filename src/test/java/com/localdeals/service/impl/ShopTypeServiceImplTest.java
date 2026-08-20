package com.localdeals.service.impl;

import com.localdeals.config.BoundedCacheProperties;
import com.localdeals.entity.ShopType;
import com.localdeals.observability.LocalDealsMetrics;
import com.localdeals.utils.CacheClient;
import com.localdeals.utils.SingleFlightLoader;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static com.localdeals.utils.RedisConstants.CACHE_SHOP_TYPE_LIST_KEY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ShopTypeServiceImplTest {

    private ValueOperations<String, String> valueOperations;
    private SimpleMeterRegistry registry;
    private CacheClient cacheClient;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        registry = new SimpleMeterRegistry();
        LocalDealsMetrics metrics = new LocalDealsMetrics(registry);
        BoundedCacheProperties properties = new BoundedCacheProperties();
        properties.setShopTypeTtl(Duration.ofMinutes(80));
        properties.setShopTypeEmptyTtl(Duration.ofSeconds(15));
        properties.validate();
        cacheClient = new CacheClient(redisTemplate, metrics, properties,
                new SingleFlightLoader(metrics));
    }

    @Test
    void returnsValidArrayHitWithoutDatabaseQuery() {
        when(valueOperations.get(CACHE_SHOP_TYPE_LIST_KEY))
                .thenReturn("[{\"id\":1,\"name\":\"Food\"},{\"id\":2,\"name\":\"Hotel\"}]");
        AtomicInteger databaseCalls = new AtomicInteger();
        ShopTypeServiceImpl service = service(() -> {
            databaseCalls.incrementAndGet();
            return Collections.emptyList();
        });

        List<ShopType> result = service.queryTypeList();

        assertThat(result).extracting(ShopType::getId).containsExactly(1L, 2L);
        assertThat(databaseCalls).hasValue(0);
        assertCacheAccess("hit", 1D);
    }

    @Test
    void emptyArrayIsAnEmptyHit() {
        when(valueOperations.get(CACHE_SHOP_TYPE_LIST_KEY)).thenReturn("[]");
        AtomicInteger databaseCalls = new AtomicInteger();

        List<ShopType> result = service(() -> {
            databaseCalls.incrementAndGet();
            return Collections.singletonList(type(1L, "unexpected"));
        }).queryTypeList();

        assertThat(result).isEmpty();
        assertThat(databaseCalls).hasValue(0);
        assertCacheAccess("empty_hit", 1D);
    }

    @Test
    void nullNonPositiveAndDuplicateIdsAreBadAndRepaired() {
        String[] badPayloads = {
                "{}",
                "[null]",
                "[{\"id\":0,\"name\":\"bad\"}]",
                "[{\"id\":1},{\"id\":1}]"
        };
        for (int index = 0; index < badPayloads.length; index++) {
            when(valueOperations.get(CACHE_SHOP_TYPE_LIST_KEY)).thenReturn(badPayloads[index]);
            List<ShopType> truth = Collections.singletonList(type((long) index + 10L, "truth"));
            assertThat(service(() -> truth).queryTypeList()).isEqualTo(truth);
        }

        assertCacheAccess("bad_value", 4D);
        verify(valueOperations, org.mockito.Mockito.times(4)).set(
                eq(CACHE_SHOP_TYPE_LIST_KEY), anyString(), eq(4_800_000L),
                eq(TimeUnit.MILLISECONDS));
    }

    @Test
    void databaseEmptyListUsesExactArrayPayloadAndShortTtl() {
        when(valueOperations.get(CACHE_SHOP_TYPE_LIST_KEY)).thenReturn(null);

        List<ShopType> result = service(Collections::emptyList).queryTypeList();

        assertThat(result).isEmpty();
        verify(valueOperations).set(CACHE_SHOP_TYPE_LIST_KEY, "[]", 15_000L,
                TimeUnit.MILLISECONDS);
        assertCacheAccess("db_empty", 1D);
    }

    private ShopTypeServiceImpl service(java.util.function.Supplier<List<ShopType>> fallback) {
        return new ShopTypeServiceImpl(cacheClient) {
            @Override
            protected List<ShopType> loadShopTypes() {
                return fallback.get();
            }
        };
    }

    private static ShopType type(Long id, String name) {
        return new ShopType().setId(id).setName(name);
    }

    private void assertCacheAccess(String result, double expected) {
        assertThat(registry.get("local_deals.cache.access")
                .tags("resource", "shop_type", "result", result)
                .counter().count()).isEqualTo(expected);
    }
}
