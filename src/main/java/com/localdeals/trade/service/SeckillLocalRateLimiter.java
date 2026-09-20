package com.localdeals.trade.service;

import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * L2 of the admission funnel: a per-instance token bucket per <em>stock bucket</em> whose rate
 * follows the stock Redis last reported for it, {@code max(minRate, remaining * factor)} permits
 * per second with a one-second burst. More requests than that cannot win anything, so they are
 * turned away before they cost a Redis round trip. A bucket with no reported stock yet is not
 * limited.
 *
 * <p>The configured floor is a per-voucher number and is shared out over the K buckets:
 * splitting a voucher must not multiply the traffic that still reaches Redis when it is nearly
 * sold out.</p>
 */
public class SeckillLocalRateLimiter {

    private final double stockFactor;
    private final double minPermitsPerSecondPerBucket;
    private final LongSupplier nanoClock;
    private final SeckillBucketRouter router;
    private final ConcurrentHashMap<Long, Bucket> buckets = new ConcurrentHashMap<>();

    public SeckillLocalRateLimiter(double stockFactor, double minPermitsPerSecond, LongSupplier nanoClock,
                                   SeckillBucketRouter router) {
        this.stockFactor = stockFactor;
        this.minPermitsPerSecondPerBucket = Math.max(1.0, minPermitsPerSecond / router.count());
        this.nanoClock = nanoClock;
        this.router = router;
    }

    public boolean tryAcquire(long voucherId, int bucket) {
        Bucket tokens = buckets.get(key(voucherId, bucket));
        return tokens == null || tokens.tryAcquire(nanoClock.getAsLong());
    }

    /** @param remainingStock stock of this bucket after an admission, or negative when unknown */
    public void observe(long voucherId, int bucket, long remainingStock) {
        if (remainingStock < 0) {
            return;
        }
        double rate = Math.max(minPermitsPerSecondPerBucket, remainingStock * stockFactor);
        long now = nanoClock.getAsLong();
        buckets.computeIfAbsent(key(voucherId, bucket), id -> new Bucket(rate, now)).setRate(rate, now);
    }

    public double rate(long voucherId, int bucket) {
        Bucket tokens = buckets.get(key(voucherId, bucket));
        return tokens == null ? Double.POSITIVE_INFINITY : tokens.rate;
    }

    /** One map for every (voucher, bucket); the bucket never reaches the gene's 1024. */
    private long key(long voucherId, int bucket) {
        if (bucket < 0 || bucket >= router.count()) {
            throw new IllegalArgumentException("bucket " + bucket + " is outside this voucher");
        }
        return voucherId * 1024L + bucket;
    }

    private static final class Bucket {
        private double rate;
        private double tokens;
        private long refilledAt;

        Bucket(double rate, long now) {
            this.rate = rate;
            this.tokens = rate;
            this.refilledAt = now;
        }

        synchronized void setRate(double newRate, long now) {
            refill(now);
            rate = newRate;
            tokens = Math.min(tokens, newRate);
        }

        synchronized boolean tryAcquire(long now) {
            refill(now);
            if (tokens < 1) {
                return false;
            }
            tokens -= 1;
            return true;
        }

        private void refill(long now) {
            if (now > refilledAt) {
                tokens = Math.min(rate, tokens + (now - refilledAt) * rate / 1_000_000_000d);
                refilledAt = now;
            }
        }
    }
}
