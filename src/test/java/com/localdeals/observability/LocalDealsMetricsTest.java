package com.localdeals.observability;

import com.localdeals.service.BlogHotRankReadResult;
import com.localdeals.service.BlogHotRankService;
import com.localdeals.dto.VoucherGrantCommand;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class LocalDealsMetricsTest {

    @Test
    void preRegistersFiniteOutcomesAndUsesNanForUnavailableGauges() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        LocalDealsMetrics metrics = new LocalDealsMetrics(registry);

        assertThat(registry.find("local_deals.blog.like.outbox.pending").gauge().value())
                .isNaN();
        assertThat(registry.find("local_deals.blog.hot_rank.read")
                .tag("result", "bad_metadata").counter()).isNotNull();
        assertThat(registry.find("local_deals.auth.request")
                .tags("flow", "admin_login", "result", "unavailable").counter()).isNotNull();
        assertThat(registry.find("local_deals.cache.access")
                .tags("resource", "shop_detail", "result", "bad_value").counter()).isNotNull();
        assertThat(registry.find("local_deals.cache.singleflight")
                .tags("resource", "shop_type", "result", "shared_timeout").counter()).isNotNull();
        assertThat(registry.find("local_deals.cache.maintenance")
                .tags("resource", "shop_detail", "operation", "write", "result", "skipped")
                .counter()).isNotNull();
        assertThat(registry.find("local_deals.traffic.decision")
                .tags("resource", "seckill", "result", "rejected", "reason", "activity")
                .counter()).isNotNull();
        assertThat(registry.find("local_deals.traffic.inflight")
                .tag("resource", "db_read").gauge()).isNotNull();
        assertThat(registry.find("local_deals.seckill.db.persist.duration")
                .tag("result", "success").timer()).isNotNull();
        assertThat(registry.find("local_deals.seckill.db.persist.duration")
                .tag("result", "failure").timer()).isNotNull();
        assertThat(registry.find("local_deals.traffic.decision")
                .tags("resource", "db_read", "result", "rejected", "reason", "ip")
                .counter()).isNull();

        metrics.updateOutboxBacklog(7L, 3.5D);
        assertThat(registry.get("local_deals.blog.like.outbox.pending").gauge().value())
                .isEqualTo(7D);
        assertThat(registry.get("local_deals.blog.like.outbox.oldest_age").gauge().value())
                .isEqualTo(3.5D);
        metrics.failOutboxCollector();
        assertThat(registry.get("local_deals.blog.like.outbox.pending").gauge().value())
                .isNaN();
    }

    @Test
    void recordsEveryHotRankReasonExactlyOnce() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        LocalDealsMetrics metrics = new LocalDealsMetrics(registry);

        metrics.recordHotRankRead(BlogHotRankReadResult.hit(Collections.singletonList(1L)));
        for (BlogHotRankReadResult.MissReason reason : BlogHotRankReadResult.MissReason.values()) {
            metrics.recordHotRankRead(BlogHotRankReadResult.miss(reason));
            assertThat(registry.get("local_deals.blog.hot_rank.read")
                    .tag("result", reason.name().toLowerCase()).counter().count()).isEqualTo(1D);
        }
        assertThat(registry.get("local_deals.blog.hot_rank.read")
                .tag("result", "hit").counter().count()).isEqualTo(1D);

        for (BlogHotRankService.RebuildOutcome outcome : BlogHotRankService.RebuildOutcome.values()) {
            metrics.recordHotRankRebuild(outcome, 1L);
        }
        assertThat(registry.get("local_deals.blog.hot_rank.rebuild").counters())
                .hasSize(BlogHotRankService.RebuildOutcome.values().length);
    }

    @Test
    void meterIdsCannotContainRequestIdentitiesOrExceptionText() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        LocalDealsMetrics metrics = new LocalDealsMetrics(registry);

        metrics.recordLike(LocalDealsMetrics.LikeOperation.LIKE,
                LocalDealsMetrics.LikeResult.FAILURE);
        metrics.recordAuth(LocalDealsMetrics.AuthFlow.USER_LOGIN,
                LocalDealsMetrics.AuthResult.REJECTED);

        String forbidden = "13800138000|user-991|order-9223372036854775807|sql timeout";
        for (Meter meter : registry.getMeters()) {
            String id = meter.getId().toString();
            for (String value : forbidden.split("\\|")) {
                assertThat(id).doesNotContain(value);
            }
        }
    }

    @Test
    void concurrentRecordingDoesNotCreateDuplicateMeterIds() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        LocalDealsMetrics metrics = new LocalDealsMetrics(registry);
        Set<Throwable> failures = Collections.newSetFromMap(new ConcurrentHashMap<Throwable, Boolean>());

        IntStream.range(0, 1_000).parallel().forEach(index -> {
            try {
                metrics.recordCache(LocalDealsMetrics.CacheResource.SHOP_DETAIL,
                        LocalDealsMetrics.CacheResult.HIT);
            } catch (Throwable failure) {
                failures.add(failure);
            }
        });

        assertThat(failures).isEmpty();
        assertThat(registry.get("local_deals.cache.access")
                .tags("resource", "shop_detail", "result", "hit")
                .counter().count()).isEqualTo(1_000D);
    }

    @Test
    void cacheMetersHaveOnlyTheFiniteM5bTagVocabulary() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        LocalDealsMetrics metrics = new LocalDealsMetrics(registry);

        metrics.recordCacheSingleFlight(LocalDealsMetrics.CacheResource.SHOP_DETAIL,
                LocalDealsMetrics.CacheSingleFlightResult.LEADER);
        metrics.recordCacheMaintenance(LocalDealsMetrics.CacheResource.SHOP_TYPE,
                LocalDealsMetrics.CacheMaintenanceOperation.EVICT,
                LocalDealsMetrics.CacheMaintenanceResult.FAILURE);

        assertThat(registry.get("local_deals.cache.access").counters())
                .hasSize(LocalDealsMetrics.CacheResource.values().length
                        * LocalDealsMetrics.CacheResult.values().length);
        assertThat(registry.get("local_deals.cache.singleflight").counters())
                .hasSize(LocalDealsMetrics.CacheResource.values().length
                        * LocalDealsMetrics.CacheSingleFlightResult.values().length);
        assertThat(registry.get("local_deals.cache.maintenance").counters())
                .hasSize(LocalDealsMetrics.CacheResource.values().length
                        * LocalDealsMetrics.CacheMaintenanceOperation.values().length
                        * LocalDealsMetrics.CacheMaintenanceResult.values().length);
        assertThat(registry.get("local_deals.cache.singleflight")
                .tags("resource", "shop_detail", "result", "leader")
                .counter().count()).isEqualTo(1D);
        assertThat(registry.get("local_deals.cache.maintenance")
                .tags("resource", "shop_type", "operation", "evict", "result", "failure")
                .counter().count()).isEqualTo(1D);
    }

    @Test
    void trafficMetersExposeOnlyLegalFiniteCombinations() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        LocalDealsMetrics metrics = new LocalDealsMetrics(registry);

        metrics.recordTraffic(LocalDealsMetrics.TrafficResource.SEARCH,
                LocalDealsMetrics.TrafficResult.REJECTED,
                LocalDealsMetrics.TrafficReason.CONCURRENCY);
        metrics.setTrafficInflight(LocalDealsMetrics.TrafficResource.SEARCH, 3);

        assertThat(registry.get("local_deals.traffic.decision").counters()).hasSize(11);
        assertThat(registry.get("local_deals.traffic.inflight").gauges()).hasSize(2);
        assertThat(registry.get("local_deals.traffic.decision")
                .tags("resource", "search", "result", "rejected", "reason", "concurrency")
                .counter().count()).isEqualTo(1D);
        assertThat(registry.get("local_deals.traffic.inflight")
                .tag("resource", "search").gauge().value()).isEqualTo(3D);
    }

    @Test
    void grantMetersUseOnlyFiniteSourceAndResultVocabulary() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        LocalDealsMetrics metrics = new LocalDealsMetrics(registry);
        VoucherGrantCommand command = new VoucherGrantCommand();
        command.setSource(VoucherGrantCommand.USER_CLAIM);

        metrics.recordGrant(command, LocalDealsMetrics.GrantResult.GRANTED);
        metrics.recordGrantDuration(1_000L);

        assertThat(registry.get("local_deals.marketing.grant").counters()).hasSize(
                LocalDealsMetrics.GrantSource.values().length *
                        LocalDealsMetrics.GrantResult.values().length);
        assertThat(registry.get("local_deals.marketing.grant")
                .tags("source", "user_claim", "result", "granted").counter().count())
                .isEqualTo(1D);
        assertThat(registry.find("local_deals.marketing.grant.duration").timer()).isNotNull();
    }
}
