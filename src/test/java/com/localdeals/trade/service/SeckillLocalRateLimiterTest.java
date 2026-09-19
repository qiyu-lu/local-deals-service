package com.localdeals.trade.service;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class SeckillLocalRateLimiterTest {

    private static final long SECOND = 1_000_000_000L;
    private final AtomicLong clock = new AtomicLong(SECOND);
    private final SeckillLocalRateLimiter limiter = new SeckillLocalRateLimiter(2.0, 50, clock::get);

    private int acquired(long voucherId, int attempts) {
        int passed = 0;
        for (int i = 0; i < attempts; i++) {
            if (limiter.tryAcquire(voucherId)) {
                passed++;
            }
        }
        return passed;
    }

    @Test
    void aVoucherWithoutReportedStockIsNotLimited() {
        assertThat(acquired(17L, 10_000)).isEqualTo(10_000);
    }

    @Test
    void permitsFollowTheRemainingStockTimesTheFactor() {
        limiter.observe(17L, 1_000);

        assertThat(acquired(17L, 10_000)).isEqualTo(2_000);
        clock.addAndGet(SECOND / 2);
        assertThat(acquired(17L, 10_000)).isEqualTo(1_000);
    }

    @Test
    void aShrinkingStockShrinksTheBucketImmediately() {
        limiter.observe(17L, 1_000);
        limiter.observe(17L, 100);

        assertThat(limiter.rate(17L)).isEqualTo(200.0);
        assertThat(acquired(17L, 10_000)).isEqualTo(200);
    }

    @Test
    void theRateNeverDropsBelowTheFloor() {
        limiter.observe(17L, 3);

        assertThat(limiter.rate(17L)).isEqualTo(50.0);
        assertThat(acquired(17L, 1_000)).isEqualTo(50);
    }

    @Test
    void unknownStockDoesNotChangeTheBucket() {
        limiter.observe(17L, 100);
        limiter.observe(17L, -1);

        assertThat(limiter.rate(17L)).isEqualTo(200.0);
    }

    @Test
    void vouchersAreIndependent() {
        limiter.observe(17L, 10);

        assertThat(acquired(17L, 1_000)).isEqualTo(50);
        assertThat(acquired(18L, 1_000)).isEqualTo(1_000);
    }
}
