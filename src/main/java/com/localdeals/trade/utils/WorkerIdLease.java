package com.localdeals.trade.utils;

import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.function.LongSupplier;

/** Stub for the red commit. */
public class WorkerIdLease {

    public static final String KEY_PREFIX = "order-id:worker:";
    public static final int MAX_WORKERS = 32;

    public WorkerIdLease(StringRedisTemplate redis, Duration ttl, LongSupplier clock) {
    }

    public int workerId() {
        throw new UnsupportedOperationException("not implemented");
    }

    public void maintain() {
        throw new UnsupportedOperationException("not implemented");
    }

    public void release() {
    }
}
