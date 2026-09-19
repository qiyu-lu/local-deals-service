package com.localdeals.trade.service;

import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * L2 of the admission funnel: a per-instance token bucket per voucher whose rate follows the
 * stock Redis last reported, {@code max(minRate, remaining * factor)} permits per second with a
 * one-second burst. More requests than that cannot win anything, so they are turned away before
 * they cost a Redis round trip. A voucher with no reported stock yet is not limited.
 */
public class SeckillLocalRateLimiter {

    private final double stockFactor;
    private final double minPermitsPerSecond;
    private final LongSupplier nanoClock;
    private final ConcurrentHashMap<Long, Bucket> buckets = new ConcurrentHashMap<>();

    public SeckillLocalRateLimiter(double stockFactor, double minPermitsPerSecond, LongSupplier nanoClock) {
        this.stockFactor = stockFactor;
        this.minPermitsPerSecond = minPermitsPerSecond;
        this.nanoClock = nanoClock;
    }

    public boolean tryAcquire(long voucherId) {
        Bucket bucket = buckets.get(voucherId);
        return bucket == null || bucket.tryAcquire(nanoClock.getAsLong());
    }

    /** @param remainingStock stock after an admission, or negative when unknown */
    public void observe(long voucherId, long remainingStock) {
        if (remainingStock < 0) {
            return;
        }
        double rate = Math.max(minPermitsPerSecond, remainingStock * stockFactor);
        long now = nanoClock.getAsLong();
        buckets.computeIfAbsent(voucherId, id -> new Bucket(rate, now)).setRate(rate, now);
    }

    public double rate(long voucherId) {
        Bucket bucket = buckets.get(voucherId);
        return bucket == null ? Double.POSITIVE_INFINITY : bucket.rate;
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
