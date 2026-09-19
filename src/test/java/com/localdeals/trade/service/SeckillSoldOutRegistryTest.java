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

class SeckillSoldOutRegistryTest {

    private final AtomicLong clock = new AtomicLong(1_000_000L);
    private StringRedisTemplate redis;
    private SeckillSoldOutRegistry registry;

    @BeforeEach
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        registry = new SeckillSoldOutRegistry(redis, Duration.ofSeconds(1), clock::get);
    }

    @Test
    void unknownVoucherGoesToRedis() {
        assertThat(registry.rejectLocally(17L)).isFalse();
    }

    @Test
    void soldOutIsAnsweredLocallyAndBroadcastOnce() {
        registry.markSoldOut(17L);
        registry.markSoldOut(17L);

        for (int i = 0; i < 1_000; i++) {
            assertThat(registry.rejectLocally(17L)).isTrue();
        }
        assertThat(registry.rejectLocally(18L)).isFalse();
        verify(redis, times(1)).convertAndSend(SeckillSoldOutRegistry.CHANNEL, "+17");
    }

    @Test
    void oneProbePerIntervalReachesRedisSoAStaleFlagHeals() {
        registry.markSoldOut(17L);
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
    void returnedStockClearsTheFlagEverywhere() {
        registry.markSoldOut(17L);

        registry.clear(17L);

        assertThat(registry.rejectLocally(17L)).isFalse();
        verify(redis).convertAndSend(SeckillSoldOutRegistry.CHANNEL, "-17");
    }

    @Test
    void peerMessagesSetAndClearWithoutEchoing() {
        registry.onMessage(message("+17"), null);
        assertThat(registry.rejectLocally(17L)).isTrue();

        registry.onMessage(message("-17"), null);
        assertThat(registry.rejectLocally(17L)).isFalse();

        registry.onMessage(message("garbage"), null);
        verify(redis, times(0)).convertAndSend(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void aFailedBroadcastStillFlagsLocally() {
        doThrow(new IllegalStateException("redis down")).when(redis)
                .convertAndSend(SeckillSoldOutRegistry.CHANNEL, "+17");

        registry.markSoldOut(17L);

        assertThat(registry.rejectLocally(17L)).isTrue();
    }

    private static DefaultMessage message(String body) {
        return new DefaultMessage(SeckillSoldOutRegistry.CHANNEL.getBytes(StandardCharsets.UTF_8),
                body.getBytes(StandardCharsets.UTF_8));
    }
}
