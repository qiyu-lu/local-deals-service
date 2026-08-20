package com.localdeals.observability;

import com.localdeals.service.BlogHotRankReadResult;
import com.localdeals.service.BlogHotRankService;
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
}
