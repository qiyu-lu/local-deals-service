package com.localdeals.trade.service;

import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.function.LongSupplier;

/** Stub for the red commit. */
public class SeckillSoldOutRegistry implements MessageListener {

    public static final String CHANNEL = "seckill:sold-out";

    public SeckillSoldOutRegistry(StringRedisTemplate redis, Duration probeInterval, LongSupplier clock) {
    }

    public boolean rejectLocally(long voucherId) {
        throw new UnsupportedOperationException("not implemented");
    }

    public boolean isSoldOut(long voucherId) {
        throw new UnsupportedOperationException("not implemented");
    }

    public void markSoldOut(long voucherId) {
        throw new UnsupportedOperationException("not implemented");
    }

    public void clear(long voucherId) {
        throw new UnsupportedOperationException("not implemented");
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        throw new UnsupportedOperationException("not implemented");
    }
}
