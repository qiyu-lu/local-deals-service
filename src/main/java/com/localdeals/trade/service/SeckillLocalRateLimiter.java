package com.localdeals.trade.service;

import java.util.function.LongSupplier;

/** Stub for the red commit. */
public class SeckillLocalRateLimiter {

    public SeckillLocalRateLimiter(double stockFactor, double minPermitsPerSecond, LongSupplier nanoClock) {
    }

    public boolean tryAcquire(long voucherId) {
        throw new UnsupportedOperationException("not implemented");
    }

    public void observe(long voucherId, long remainingStock) {
        throw new UnsupportedOperationException("not implemented");
    }

    public double rate(long voucherId) {
        throw new UnsupportedOperationException("not implemented");
    }
}
