package com.localdeals.utils;

import com.localdeals.observability.LocalDealsMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

class SingleFlightLoaderTest {

    @Test
    void thirtyTwoOverlappingRequestsExecuteOneCallback() throws Exception {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        SingleFlightLoader loader = loader(registry);
        ExecutorService executor = Executors.newFixedThreadPool(32);
        CountDownLatch ready = new CountDownLatch(32);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch callbackEntered = new CountDownLatch(1);
        CountDownLatch releaseCallback = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        List<Future<String>> futures = new ArrayList<>();
        try {
            for (int index = 0; index < 32; index++) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    return loader.load(LocalDealsMetrics.CacheResource.SHOP_DETAIL, "cache:shop:1", () -> {
                        calls.incrementAndGet();
                        callbackEntered.countDown();
                        releaseCallback.await();
                        return "shop";
                    });
                }));
            }
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            assertThat(callbackEntered.await(5, TimeUnit.SECONDS)).isTrue();
            awaitCounter(registry, LocalDealsMetrics.CacheResource.SHOP_DETAIL, "shared", 31D);
            releaseCallback.countDown();

            for (Future<String> future : futures) {
                assertThat(future.get(5, TimeUnit.SECONDS)).isEqualTo("shop");
            }
            assertThat(calls).hasValue(1);
            assertThat(loader.inFlightCount()).isZero();
            assertThat(registry.get("local_deals.cache.singleflight")
                    .tags("resource", "shop_detail", "result", "leader")
                    .counter().count()).isEqualTo(1D);
            assertThat(registry.get("local_deals.cache.singleflight")
                    .tags("resource", "shop_detail", "result", "shared")
                    .counter().count()).isEqualTo(31D);
        } finally {
            releaseCallback.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void sharesNullResultWithEveryFollower() throws Exception {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        SingleFlightLoader loader = loader(registry);
        ExecutorService executor = Executors.newFixedThreadPool(8);
        CountDownLatch callbackEntered = new CountDownLatch(1);
        CountDownLatch releaseCallback = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        List<Future<Object>> futures = new ArrayList<>();
        try {
            for (int index = 0; index < 8; index++) {
                futures.add(executor.submit(() -> loader.load(
                        LocalDealsMetrics.CacheResource.SHOP_TYPE, "cache:shop:type:list:", () -> {
                            calls.incrementAndGet();
                            callbackEntered.countDown();
                            releaseCallback.await();
                            return null;
                        })));
            }
            assertThat(callbackEntered.await(5, TimeUnit.SECONDS)).isTrue();
            awaitCounter(registry, LocalDealsMetrics.CacheResource.SHOP_TYPE, "shared", 7D);
            releaseCallback.countDown();
            for (Future<Object> future : futures) {
                assertThat(future.get(5, TimeUnit.SECONDS)).isNull();
            }
            assertThat(calls).hasValue(1);
            assertThat(loader.inFlightCount()).isZero();
        } finally {
            releaseCallback.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void sharesLeaderFailureAndRemovesEntry() throws Exception {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        SingleFlightLoader loader = loader(registry);
        ExecutorService executor = Executors.newFixedThreadPool(8);
        CountDownLatch callbackEntered = new CountDownLatch(1);
        CountDownLatch releaseCallback = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        RuntimeException databaseFailure = new IllegalStateException("db unavailable");
        List<Future<Object>> futures = new ArrayList<>();
        try {
            for (int index = 0; index < 8; index++) {
                futures.add(executor.submit(() -> loader.load(
                        LocalDealsMetrics.CacheResource.SHOP_DETAIL, "cache:shop:2", () -> {
                            calls.incrementAndGet();
                            callbackEntered.countDown();
                            releaseCallback.await();
                            throw databaseFailure;
                        })));
            }
            assertThat(callbackEntered.await(5, TimeUnit.SECONDS)).isTrue();
            awaitCounter(registry, LocalDealsMetrics.CacheResource.SHOP_DETAIL, "shared", 7D);
            releaseCallback.countDown();
            for (Future<Object> future : futures) {
                assertThatThrownBy(() -> future.get(5, TimeUnit.SECONDS))
                        .hasCause(databaseFailure);
            }
            assertThat(calls).hasValue(1);
            assertThat(loader.inFlightCount()).isZero();
        } finally {
            releaseCallback.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void interruptedFollowerRestoresFlagWithoutRemovingLeaderEntry() throws Exception {
        LocalDealsMetrics metrics = mock(LocalDealsMetrics.class);
        CountDownLatch followerJoined = new CountDownLatch(1);
        doAnswer(invocation -> {
            if (invocation.getArgument(1) == LocalDealsMetrics.CacheSingleFlightResult.SHARED) {
                followerJoined.countDown();
            }
            return null;
        }).when(metrics).recordCacheSingleFlight(any(), any());
        SingleFlightLoader loader = new SingleFlightLoader(metrics);
        CountDownLatch callbackEntered = new CountDownLatch(1);
        CountDownLatch releaseCallback = new CountDownLatch(1);
        AtomicReference<Throwable> followerFailure = new AtomicReference<>();
        AtomicBoolean interruptedFlag = new AtomicBoolean();

        Thread leader = new Thread(() -> loader.load(LocalDealsMetrics.CacheResource.SHOP_DETAIL,
                "cache:shop:3", () -> {
                    callbackEntered.countDown();
                    releaseCallback.await();
                    return "shop";
                }), "singleflight-test-leader");
        Thread follower = new Thread(() -> {
            try {
                loader.load(LocalDealsMetrics.CacheResource.SHOP_DETAIL,
                        "cache:shop:3", () -> "unexpected");
            } catch (Throwable failure) {
                followerFailure.set(failure);
                interruptedFlag.set(Thread.currentThread().isInterrupted());
            }
        }, "singleflight-test-follower");

        leader.start();
        assertThat(callbackEntered.await(5, TimeUnit.SECONDS)).isTrue();
        follower.start();
        assertThat(followerJoined.await(5, TimeUnit.SECONDS)).isTrue();
        follower.interrupt();
        follower.join(5_000L);

        assertThat(follower.isAlive()).isFalse();
        assertThat(followerFailure.get())
                .isInstanceOf(SingleFlightLoader.SingleFlightInterruptedException.class);
        assertThat(interruptedFlag).isTrue();
        assertThat(loader.inFlightCount()).isEqualTo(1);

        releaseCallback.countDown();
        leader.join(5_000L);
        assertThat(leader.isAlive()).isFalse();
        assertThat(loader.inFlightCount()).isZero();
    }

    private static SingleFlightLoader loader(SimpleMeterRegistry registry) {
        return new SingleFlightLoader(new LocalDealsMetrics(registry));
    }

    private static void awaitCounter(SimpleMeterRegistry registry,
                                     LocalDealsMetrics.CacheResource resource,
                                     String result, double expected) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (registry.get("local_deals.cache.singleflight")
                .tags("resource", resource.name().toLowerCase(), "result", result)
                .counter().count() != expected && System.nanoTime() < deadline) {
            Thread.yield();
        }
        assertThat(registry.get("local_deals.cache.singleflight")
                .tags("resource", resource.name().toLowerCase(), "result", result)
                .counter().count()).isEqualTo(expected);
    }
}
