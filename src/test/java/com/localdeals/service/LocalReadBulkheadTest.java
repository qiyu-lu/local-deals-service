package com.localdeals.service;

import com.localdeals.config.TrafficControlProperties;
import com.localdeals.exception.ApiErrorCodes;
import com.localdeals.exception.ApiStatusException;
import com.localdeals.observability.LocalDealsMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LocalReadBulkheadTest {
    @Test
    void dbAndSearchEachCapConcurrentEntryAndRejectOverflow() throws Exception {
        assertConcurrencyCap(LocalReadBulkhead.Resource.DB_READ, ApiErrorCodes.READ_OVERLOADED);
        assertConcurrencyCap(LocalReadBulkhead.Resource.SEARCH, ApiErrorCodes.SEARCH_OVERLOADED);
    }

    @Test
    void successFailureAndInterruptionNeverLeakPermit() throws Exception {
        TrafficControlProperties properties = properties(1, Duration.ofMillis(100));
        LocalReadBulkhead bulkhead = bulkhead(properties);

        assertThat(bulkhead.executeDbRead(() -> "ok")).isEqualTo("ok");
        assertThatThrownBy(() -> bulkhead.executeDbRead(() -> {
            throw new IllegalStateException("database down");
        })).isInstanceOfSatisfying(ApiStatusException.class, error ->
                assertThat(error.getCode()).isEqualTo(ApiErrorCodes.DATABASE_UNAVAILABLE));
        assertThat(bulkhead.executeDbRead(() -> "after-failure")).isEqualTo("after-failure");

        CountDownLatch holderEntered = new CountDownLatch(1);
        CountDownLatch releaseHolder = new CountDownLatch(1);
        Thread holder = new Thread(() -> bulkhead.executeDbRead(() -> {
            holderEntered.countDown();
            await(releaseHolder);
            return null;
        }));
        AtomicReference<Throwable> interruptedFailure = new AtomicReference<>();
        AtomicBoolean interruptRestored = new AtomicBoolean();
        Thread waiter = new Thread(() -> {
            try {
                bulkhead.executeDbRead(() -> "unexpected");
            } catch (Throwable failure) {
                interruptedFailure.set(failure);
                interruptRestored.set(Thread.currentThread().isInterrupted());
            }
        });
        holder.start();
        assertThat(holderEntered.await(5, TimeUnit.SECONDS)).isTrue();
        waiter.start();
        waiter.interrupt();
        waiter.join(5_000L);
        assertThat(interruptedFailure.get()).isInstanceOfSatisfying(ApiStatusException.class,
                error -> assertThat(error.getCode()).isEqualTo(ApiErrorCodes.DATABASE_UNAVAILABLE));
        assertThat(interruptRestored).isTrue();
        releaseHolder.countDown();
        holder.join(5_000L);
        assertThat(bulkhead.executeDbRead(() -> "after-interrupt")).isEqualTo("after-interrupt");
    }

    private void assertConcurrencyCap(LocalReadBulkhead.Resource resource,
                                      String expectedCode) throws Exception {
        TrafficControlProperties properties = properties(4, Duration.ofMillis(20));
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        LocalReadBulkhead bulkhead = new LocalReadBulkhead(
                properties, new LocalDealsMetrics(registry));
        ExecutorService executor = Executors.newFixedThreadPool(4);
        CountDownLatch entered = new CountDownLatch(4);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger current = new AtomicInteger();
        AtomicInteger maximum = new AtomicInteger();
        List<Future<?>> holders = new ArrayList<>();
        try {
            for (int index = 0; index < 4; index++) {
                holders.add(executor.submit(() -> execute(bulkhead, resource, () -> {
                    int value = current.incrementAndGet();
                    maximum.accumulateAndGet(value, Math::max);
                    entered.countDown();
                    await(release);
                    current.decrementAndGet();
                    return null;
                })));
            }
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            for (int index = 0; index < 20; index++) {
                long started = System.nanoTime();
                assertThatThrownBy(() -> execute(bulkhead, resource, () -> "overflow"))
                        .isInstanceOfSatisfying(ApiStatusException.class,
                                error -> assertThat(error.getCode()).isEqualTo(expectedCode));
                assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started))
                        .isLessThan(250L);
            }
            assertThat(maximum).hasValue(4);
        } finally {
            release.countDown();
            for (Future<?> holder : holders) {
                holder.get(5, TimeUnit.SECONDS);
            }
            executor.shutdownNow();
        }
        assertThat(execute(bulkhead, resource, () -> "after")).isEqualTo("after");
    }

    private static TrafficControlProperties properties(int maxConcurrent, Duration maxWait) {
        TrafficControlProperties properties = new TrafficControlProperties();
        properties.getRead().setDbMaxConcurrent(maxConcurrent);
        properties.getRead().setSearchMaxConcurrent(maxConcurrent);
        properties.getRead().setDbMaxWait(maxWait);
        properties.getRead().setSearchMaxWait(maxWait);
        properties.validate();
        return properties;
    }

    private static LocalReadBulkhead bulkhead(TrafficControlProperties properties) {
        return new LocalReadBulkhead(properties,
                new LocalDealsMetrics(new SimpleMeterRegistry()));
    }

    private static <T> T execute(LocalReadBulkhead bulkhead,
                                 LocalReadBulkhead.Resource resource,
                                 java.util.function.Supplier<T> callback) {
        return resource == LocalReadBulkhead.Resource.DB_READ
                ? bulkhead.executeDbRead(callback)
                : bulkhead.executeSearch(callback);
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(interrupted);
        }
    }
}
