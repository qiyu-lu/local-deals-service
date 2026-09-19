package com.localdeals.trade.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * L1 of the admission funnel: a JVM-local "sold out" flag per voucher. Once the Redis script
 * reports no stock, later requests are answered here with no network IO at all.
 *
 * <p>Flags are shared through Redis pub/sub ({@code +id} sets, {@code -id} clears) and cleared
 * whenever a unit goes back to Redis (close, refund, compensation). Pub/sub is fire-and-forget
 * and messages from different instances can cross, so a flagged voucher still lets one probe
 * request per interval through to Redis: a stale flag heals within that interval.</p>
 */
@Slf4j
public class SeckillSoldOutRegistry implements MessageListener {

    public static final String CHANNEL = "seckill:sold-out";

    private final StringRedisTemplate redis;
    private final long probeIntervalMillis;
    private final LongSupplier clock;
    /** voucherId -> earliest time the next probe may pass */
    private final ConcurrentHashMap<Long, AtomicLong> soldOut = new ConcurrentHashMap<>();

    public SeckillSoldOutRegistry(StringRedisTemplate redis, Duration probeInterval, LongSupplier clock) {
        this.redis = redis;
        this.probeIntervalMillis = probeInterval.toMillis();
        this.clock = clock;
    }

    /** @return true when the request can be answered "sold out" locally */
    public boolean rejectLocally(long voucherId) {
        AtomicLong nextProbe = soldOut.get(voucherId);
        if (nextProbe == null) {
            return false;
        }
        long now = clock.getAsLong();
        long due = nextProbe.get();
        // Exactly one request per interval wins the CAS and goes on to Redis as the probe.
        return now < due || !nextProbe.compareAndSet(due, now + probeIntervalMillis);
    }

    public boolean isSoldOut(long voucherId) {
        return soldOut.containsKey(voucherId);
    }

    public void markSoldOut(long voucherId) {
        if (setLocally(voucherId)) {
            publish("+" + voucherId);
        }
    }

    public void clear(long voucherId) {
        soldOut.remove(voucherId);
        publish("-" + voucherId);
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        String body = new String(message.getBody(), StandardCharsets.UTF_8);
        try {
            long voucherId = Long.parseLong(body.substring(1));
            if (body.charAt(0) == '+') {
                setLocally(voucherId);
            } else if (body.charAt(0) == '-') {
                soldOut.remove(voucherId);
            }
        } catch (RuntimeException e) {
            log.warn("Ignoring malformed sold-out message: {}", body);
        }
    }

    private boolean setLocally(long voucherId) {
        return soldOut.putIfAbsent(voucherId, new AtomicLong(clock.getAsLong() + probeIntervalMillis)) == null;
    }

    private void publish(String body) {
        try {
            redis.convertAndSend(CHANNEL, body);
        } catch (RuntimeException e) {
            // Other instances fall back to their own probe.
            log.warn("Sold-out broadcast failed: {}", body, e);
        }
    }
}
