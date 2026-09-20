package com.localdeals.trade.service;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * L2 follows the stock of one bucket, because that is the stock the admission script reports
 * back to a buyer. The configured floor stays a per-voucher number and is shared out over the
 * buckets, so splitting a voucher does not multiply the traffic Redis sees.
 */
class SeckillLocalRateLimiterTest {

    private static final long SECOND = 1_000_000_000L;
    private static final int BUCKETS = 4;
    private final AtomicLong clock = new AtomicLong(SECOND);
    private final SeckillLocalRateLimiter limiter =
            new SeckillLocalRateLimiter(2.0, 200, clock::get, new SeckillBucketRouter(BUCKETS));

    private int acquired(long voucherId, int bucket, int attempts) {
        int passed = 0;
        for (int i = 0; i < attempts; i++) {
            if (limiter.tryAcquire(voucherId, bucket)) {
                passed++;
            }
        }
        return passed;
    }

    @Test
    void aVoucherWithoutReportedStockIsNotLimited() {
        assertThat(acquired(17L, 1, 10_000)).isEqualTo(10_000);
    }

    @Test
    void permitsFollowTheRemainingStockTimesTheFactor() {
        limiter.observe(17L, 1, 1_000);

        assertThat(acquired(17L, 1, 10_000)).isEqualTo(2_000);
        clock.addAndGet(SECOND / 2);
        assertThat(acquired(17L, 1, 10_000)).isEqualTo(1_000);
    }

    @Test
    void aShrinkingStockShrinksTheBucketImmediately() {
        limiter.observe(17L, 1, 1_000);
        limiter.observe(17L, 1, 100);

        assertThat(limiter.rate(17L, 1)).isEqualTo(200.0);
        assertThat(acquired(17L, 1, 10_000)).isEqualTo(200);
    }

    @Test
    void theRateNeverDropsBelowTheFloorSharedOutOverTheBuckets() {
        limiter.observe(17L, 1, 3);

        // 200 permits per voucher, four buckets: 50 per bucket.
        assertThat(limiter.rate(17L, 1)).isEqualTo(50.0);
        assertThat(acquired(17L, 1, 1_000)).isEqualTo(50);
    }

    @Test
    void unknownStockDoesNotChangeTheBucket() {
        limiter.observe(17L, 1, 100);
        limiter.observe(17L, 1, -1);

        assertThat(limiter.rate(17L, 1)).isEqualTo(200.0);
    }

    @Test
    void bucketsOfOneVoucherAreIndependent() {
        limiter.observe(17L, 1, 10);

        assertThat(acquired(17L, 1, 1_000)).isEqualTo(50);
        assertThat(acquired(17L, 2, 1_000)).isEqualTo(1_000);
    }

    @Test
    void vouchersAreIndependent() {
        limiter.observe(17L, 1, 10);

        assertThat(acquired(17L, 1, 1_000)).isEqualTo(50);
        assertThat(acquired(18L, 1, 1_000)).isEqualTo(1_000);
    }
}
