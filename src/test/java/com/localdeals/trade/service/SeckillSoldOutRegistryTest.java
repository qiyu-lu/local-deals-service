package com.localdeals.trade.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.DefaultMessage;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * L1 flags a stock bucket, not a voucher. The interceptor that consults it runs before
 * authentication and has no user, so it may only answer "sold out" once every bucket is empty.
 */
class SeckillSoldOutRegistryTest {

    private static final int BUCKETS = 4;

    private final AtomicLong clock = new AtomicLong(1_000_000L);
    private StringRedisTemplate redis;
    private SeckillSoldOutRegistry registry;

    @BeforeEach
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        registry = new SeckillSoldOutRegistry(redis, Duration.ofSeconds(1), clock::get,
                new SeckillBucketRouter(BUCKETS));
    }

    @Test
    void unknownVoucherGoesToRedis() {
        assertThat(registry.rejectLocally(17L)).isFalse();
    }

    @Test
    void oneEmptyBucketMustNotRejectTheOtherBuyers() {
        registry.markSoldOut(17L, 0);
        registry.markSoldOut(17L, 1);
        registry.markSoldOut(17L, 2);

        assertThat(registry.rejectLocally(17L)).isFalse();
        assertThat(registry.isSoldOut(17L, 0)).isTrue();
        assertThat(registry.isSoldOut(17L, 3)).isFalse();
    }

    @Test
    void everyBucketEmptyIsAnsweredLocallyAndEachBucketIsBroadcastOnce() {
        for (int bucket = 0; bucket < BUCKETS; bucket++) {
            registry.markSoldOut(17L, bucket);
            registry.markSoldOut(17L, bucket);
        }

        for (int i = 0; i < 1_000; i++) {
            assertThat(registry.rejectLocally(17L)).isTrue();
        }
        assertThat(registry.rejectLocally(18L)).isFalse();
        verify(redis, times(1)).convertAndSend(SeckillSoldOutRegistry.CHANNEL, "+17:0");
        verify(redis, times(1)).convertAndSend(SeckillSoldOutRegistry.CHANNEL, "+17:3");
    }

    @Test
    void oneProbePerIntervalReachesRedisSoAStaleFlagHeals() {
        markEveryBucket(17L);
        clock.addAndGet(1_000);

        int probes = 0;
        for (int i = 0; i < 100; i++) {
            if (!registry.rejectLocally(17L)) {
                probes++;
            }
        }
        assertThat(probes).isEqualTo(1);
        clock.addAndGet(999);
        assertThat(registry.rejectLocally(17L)).isTrue();
        clock.addAndGet(1);
        assertThat(registry.rejectLocally(17L)).isFalse();
    }

    @Test
    void oneReturnedUnitReopensItsOwnBucketOnly() {
        markEveryBucket(17L);

        registry.clear(17L, 2);

        assertThat(registry.rejectLocally(17L)).isFalse();
        assertThat(registry.isSoldOut(17L, 2)).isFalse();
        assertThat(registry.isSoldOut(17L, 1)).isTrue();
        verify(redis).convertAndSend(SeckillSoldOutRegistry.CHANNEL, "-17:2");
    }

    @Test
    void peerMessagesSetAndClearWithoutEchoing() {
        for (int bucket = 0; bucket < BUCKETS; bucket++) {
            registry.onMessage(message("+17:" + bucket), null);
        }
        assertThat(registry.rejectLocally(17L)).isTrue();

        registry.onMessage(message("-17:1"), null);
        assertThat(registry.rejectLocally(17L)).isFalse();

        registry.onMessage(message("garbage"), null);
        registry.onMessage(message("+17"), null);
        registry.onMessage(message("+17:99"), null);
        verify(redis, times(0)).convertAndSend(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void aFailedBroadcastStillFlagsLocally() {
        doThrow(new IllegalStateException("redis down")).when(redis)
                .convertAndSend(SeckillSoldOutRegistry.CHANNEL, "+17:0");

        markEveryBucket(17L);

        assertThat(registry.rejectLocally(17L)).isTrue();
    }

    private void markEveryBucket(long voucherId) {
        for (int bucket = 0; bucket < BUCKETS; bucket++) {
            registry.markSoldOut(voucherId, bucket);
        }
    }

    private static DefaultMessage message(String body) {
        return new DefaultMessage(SeckillSoldOutRegistry.CHANNEL.getBytes(StandardCharsets.UTF_8),
                body.getBytes(StandardCharsets.UTF_8));
    }
}
