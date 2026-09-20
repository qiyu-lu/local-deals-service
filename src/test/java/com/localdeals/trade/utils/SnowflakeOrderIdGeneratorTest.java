package com.localdeals.trade.utils;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SnowflakeOrderIdGeneratorTest {

    private static final long NOW = Instant.parse("2026-09-19T12:00:00Z").toEpochMilli();

    @Test
    void idsAreUniqueUnderConcurrencyWithZeroNetworkCalls() throws InterruptedException {
        SnowflakeOrderIdGenerator generator = new SnowflakeOrderIdGenerator(() -> 3, System::currentTimeMillis);
        int threads = 16;
        int perThread = 20_000;
        Set<Long> ids = ConcurrentHashMap.newKeySet();
        CountDownLatch done = new CountDownLatch(threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        for (int t = 0; t < threads; t++) {
            int user = t;
            pool.submit(() -> {
                try {
                    for (int i = 0; i < perThread; i++) {
                        ids.add(generator.nextId(user));
                    }
                } finally {
                    done.countDown();
                }
            });
        }
        assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
        pool.shutdown();

        assertThat(ids).hasSize(threads * perThread);
    }

    @Test
    void lowTenBitsCarryTheUserGeneSoAnOrderRoutesLikeItsUser() {
        SnowflakeOrderIdGenerator generator = new SnowflakeOrderIdGenerator(() -> 0, () -> NOW);
        for (long userId : new long[]{1L, 1023L, 1024L, 1025L, 987_654_321L}) {
            long id = generator.nextId(userId);
            assertThat(SnowflakeOrderIdGenerator.geneOf(id)).isEqualTo(userId % 1024);
            assertThat(id % 8).isEqualTo(userId % 8);
        }
    }

    @Test
    void everyNewIdIsAboveEveryV1RedisIdSoTheTwoRangesNeverMeet() {
        // V1 ids were (seconds since 2025-12-22 << 32) | count and stopped before 2^25 seconds
        // had elapsed, so every one of them is below 2^57.
        long lastV1Id = ((NOW / 1000 - 1_766_432_420L) << 32) | 0xFFFF_FFFFL;
        assertThat(lastV1Id).isLessThan(1L << 57);

        SnowflakeOrderIdGenerator generator = new SnowflakeOrderIdGenerator(() -> 31, () -> NOW);
        assertThat(generator.nextId(0L)).isGreaterThanOrEqualTo(1L << 57).isGreaterThan(lastV1Id);
    }

    @Test
    void aClockBeforeTheSafeRangeIsRefusedInsteadOfIssuingALowId() {
        long beforeFebruary2026 = Instant.parse("2026-01-15T00:00:00Z").toEpochMilli();
        SnowflakeOrderIdGenerator generator = new SnowflakeOrderIdGenerator(() -> 0, () -> beforeFebruary2026);

        assertThatThrownBy(() -> generator.nextId(1L)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void idsFromOneWorkerIncreaseWithTimeAndSequence() {
        AtomicLong clock = new AtomicLong(NOW);
        SnowflakeOrderIdGenerator generator = new SnowflakeOrderIdGenerator(() -> 5, clock::get);
        long first = generator.nextId(9L);
        long sameMillisecond = generator.nextId(9L);
        clock.addAndGet(1);
        long nextMillisecond = generator.nextId(9L);

        assertThat(sameMillisecond).isGreaterThan(first);
        assertThat(nextMillisecond).isGreaterThan(sameMillisecond);
    }

    @Test
    void sequenceOverflowWaitsForTheNextMillisecond() {
        // The clock only moves forward once the generator has read it 200 times.
        AtomicLong reads = new AtomicLong();
        LongSupplier clock = () -> NOW + reads.incrementAndGet() / 200;
        SnowflakeOrderIdGenerator generator = new SnowflakeOrderIdGenerator(() -> 1, clock);
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < 129; i++) {
            ids.add(generator.nextId(4L));
        }

        assertThat(ids).doesNotHaveDuplicates().isSorted();
        long firstMillis = ids.get(0) >>> 22;
        long lastMillis = ids.get(128) >>> 22;
        assertThat(lastMillis).isGreaterThan(firstMillis);
    }

    @Test
    void smallClockRollbackWaitsAndLargeRollbackFailsClosed() {
        AtomicLong clock = new AtomicLong(NOW);
        SnowflakeOrderIdGenerator generator = new SnowflakeOrderIdGenerator(() -> 2, () -> {
            long value = clock.get();
            clock.compareAndSet(NOW - 3, NOW); // a small NTP step back heals by itself
            return value;
        });
        long before = generator.nextId(1L);
        clock.set(NOW - 3);
        long afterSmallRollback = generator.nextId(1L);
        assertThat(afterSmallRollback).isGreaterThan(before);

        clock.set(NOW - 60_000);
        assertThatThrownBy(() -> generator.nextId(1L)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void differentWorkersNeverCollideInTheSameMillisecondForTheSameUser() {
        SnowflakeOrderIdGenerator a = new SnowflakeOrderIdGenerator(() -> 7, () -> NOW);
        SnowflakeOrderIdGenerator b = new SnowflakeOrderIdGenerator(() -> 8, () -> NOW);

        assertThat(a.nextId(42L)).isNotEqualTo(b.nextId(42L));
    }

    @Test
    void noHeldWorkerIdMeansNoId() {
        SnowflakeOrderIdGenerator unheld = new SnowflakeOrderIdGenerator(() -> {
            throw new IllegalStateException("lease not held");
        }, () -> NOW);
        SnowflakeOrderIdGenerator outOfRange = new SnowflakeOrderIdGenerator(() -> 32, () -> NOW);

        assertThatThrownBy(() -> unheld.nextId(1L)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> outOfRange.nextId(1L)).isInstanceOf(IllegalStateException.class);
    }
}
