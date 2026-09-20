package com.localdeals.trade.utils;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WorkerIdLeaseTest {

    private static final Duration TTL = Duration.ofSeconds(30);

    private StringRedisTemplate redis;
    private ValueOperations<String, String> values;
    private final AtomicLong clock = new AtomicLong(1_000_000L);

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
    }

    private WorkerIdLease lease() {
        return new WorkerIdLease(redis, TTL, clock::get);
    }

    @Test
    void takesTheFirstFreeSlot() {
        when(values.setIfAbsent(anyString(), anyString(), eq(TTL))).thenReturn(false);
        when(values.setIfAbsent(eq(WorkerIdLease.KEY_PREFIX + "0"), anyString(), eq(TTL))).thenReturn(false);
        when(values.setIfAbsent(eq(WorkerIdLease.KEY_PREFIX + "5"), anyString(), eq(TTL))).thenReturn(true);
        WorkerIdLease lease = lease();

        lease.maintain();

        assertThat(lease.workerId()).isEqualTo(5);
    }

    @Test
    void withoutALeaseThereIsNoWorkerId() {
        when(values.setIfAbsent(anyString(), anyString(), eq(TTL))).thenReturn(false);
        WorkerIdLease lease = lease();

        lease.maintain();

        assertThatThrownBy(lease::workerId).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void stopsIssuingBeforeRedisCouldHaveExpiredTheKeyWhenRenewalsFail() {
        when(values.setIfAbsent(anyString(), anyString(), eq(TTL))).thenReturn(true);
        when(redis.execute(any(RedisScript.class), anyList(), any(), any()))
                .thenThrow(new IllegalStateException("redis down"));
        WorkerIdLease lease = lease();
        lease.maintain();
        int held = lease.workerId();

        clock.addAndGet(TTL.toMillis() / 3);
        lease.maintain();
        assertThat(lease.workerId()).isEqualTo(held);

        clock.addAndGet(TTL.toMillis() / 2);
        lease.maintain();
        assertThatThrownBy(lease::workerId).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void aSuccessfulRenewalExtendsTheFence() {
        when(values.setIfAbsent(anyString(), anyString(), eq(TTL))).thenReturn(true);
        when(redis.execute(any(RedisScript.class), anyList(), any(), any())).thenReturn(1L);
        WorkerIdLease lease = lease();
        lease.maintain();
        int held = lease.workerId();

        for (int i = 0; i < 10; i++) {
            clock.addAndGet(TTL.toMillis() / 3);
            lease.maintain();
        }

        assertThat(lease.workerId()).isEqualTo(held);
    }

    @Test
    void aStolenKeyDropsTheSlotAndTakesAnotherOne() {
        when(values.setIfAbsent(anyString(), anyString(), eq(TTL))).thenReturn(false);
        when(values.setIfAbsent(eq(WorkerIdLease.KEY_PREFIX + "0"), anyString(), eq(TTL))).thenReturn(true, false);
        when(values.setIfAbsent(eq(WorkerIdLease.KEY_PREFIX + "1"), anyString(), eq(TTL))).thenReturn(true);
        when(redis.execute(any(RedisScript.class), anyList(), any(), any())).thenReturn(0L);
        WorkerIdLease lease = lease();
        lease.maintain();
        assertThat(lease.workerId()).isZero();

        clock.addAndGet(TTL.toMillis() / 3);
        lease.maintain();

        assertThat(lease.workerId()).isEqualTo(1);
    }
}
