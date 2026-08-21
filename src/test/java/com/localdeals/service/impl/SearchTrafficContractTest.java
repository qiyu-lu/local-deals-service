package com.localdeals.service.impl;

import com.localdeals.config.TrafficControlProperties;
import com.localdeals.exception.ApiErrorCodes;
import com.localdeals.exception.ApiStatusException;
import com.localdeals.mapper.ShopMapper;
import com.localdeals.observability.LocalDealsMetrics;
import com.localdeals.service.LocalReadBulkhead;
import com.localdeals.utils.CacheClient;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.data.elasticsearch.core.ElasticsearchRestTemplate;
import org.springframework.data.elasticsearch.core.mapping.IndexCoordinates;
import org.springframework.data.elasticsearch.core.query.Query;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class SearchTrafficContractTest {
    @Test
    void elasticsearchFailureReturns503WithoutMysqlLikeFallback() {
        ElasticsearchRestTemplate elasticsearch = mock(ElasticsearchRestTemplate.class);
        ShopMapper mapper = mock(ShopMapper.class);
        ShopServiceImpl service = new ShopServiceImpl(mock(StringRedisTemplate.class),
                mock(CacheClient.class), elasticsearch, bulkhead(new TrafficControlProperties()));
        ReflectionTestUtils.setField(service, "baseMapper", mapper);
        when(elasticsearch.search(any(Query.class), eq(com.localdeals.dto.ShopDoc.class),
                any(IndexCoordinates.class))).thenThrow(new RuntimeException("es down"));

        assertThatThrownBy(() -> service.searchShops("hotpot", null, null, null, null, 1))
                .isInstanceOfSatisfying(ApiStatusException.class, error -> {
                    assertThat(error.getStatus().value()).isEqualTo(503);
                    assertThat(error.getCode()).isEqualTo(ApiErrorCodes.SEARCH_UNAVAILABLE);
                });
        verifyNoInteractions(mapper);
    }

    @Test
    void legacyNameSearchDoesNotReachMysqlWhenSearchPermitIsExhausted() throws Exception {
        TrafficControlProperties properties = new TrafficControlProperties();
        properties.getRead().setSearchMaxConcurrent(1);
        properties.getRead().setSearchMaxWait(Duration.ZERO);
        LocalReadBulkhead bulkhead = bulkhead(properties);
        ShopMapper mapper = mock(ShopMapper.class);
        ShopServiceImpl service = new ShopServiceImpl(mock(StringRedisTemplate.class),
                mock(CacheClient.class), mock(ElasticsearchRestTemplate.class), bulkhead);
        ReflectionTestUtils.setField(service, "baseMapper", mapper);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread holder = new Thread(() -> bulkhead.executeSearch(() -> {
            entered.countDown();
            try {
                release.await();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            return null;
        }));
        holder.start();
        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
        try {
            assertThatThrownBy(() -> service.queryShopByName("tea", 1))
                    .isInstanceOfSatisfying(ApiStatusException.class,
                            error -> assertThat(error.getCode()).isEqualTo(
                                    ApiErrorCodes.SEARCH_OVERLOADED));
            verifyNoInteractions(mapper);
        } finally {
            release.countDown();
            holder.join(5_000L);
        }
    }

    private static LocalReadBulkhead bulkhead(TrafficControlProperties properties) {
        properties.validate();
        return new LocalReadBulkhead(properties,
                new LocalDealsMetrics(new SimpleMeterRegistry()));
    }
}
