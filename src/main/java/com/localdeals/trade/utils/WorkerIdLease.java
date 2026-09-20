package com.localdeals.trade.utils;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.Duration;
import java.util.Collections;
import java.util.UUID;
import java.util.function.LongSupplier;

/**
 * Holds one of the 32 Snowflake worker ids as a Redis lease ({@code SET NX PX}, renewed every
 * ttl/3). The instance fences itself on its own clock: it stops issuing ids a sixth of the ttl
 * before Redis could have expired the key, so while renewals fail no second instance can take the
 * slot and overlap with this one.
 */
@Slf4j
public class WorkerIdLease {

    public static final String KEY_PREFIX = "order-id:worker:";
    public static final int MAX_WORKERS = SnowflakeOrderIdGenerator.MAX_WORKERS;

    private static final RedisScript<Long> RENEW = new DefaultRedisScript<>(
            "if redis.call('GET', KEYS[1]) == ARGV[1] then " +
                    "return redis.call('PEXPIRE', KEYS[1], ARGV[2]) end return 0", Long.class);
    private static final RedisScript<Long> RELEASE = new DefaultRedisScript<>(
            "if redis.call('GET', KEYS[1]) == ARGV[1] then " +
                    "return redis.call('DEL', KEYS[1]) end return 0", Long.class);

    private final StringRedisTemplate redis;
    private final Duration ttl;
    private final LongSupplier clock;
    private final String token = UUID.randomUUID().toString();
    private volatile int workerId = -1;
    private volatile long validUntil;

    public WorkerIdLease(StringRedisTemplate redis, Duration ttl, LongSupplier clock) {
        this.redis = redis;
        this.ttl = ttl;
        this.clock = clock;
    }

    /** @throws IllegalStateException when no lease is held or its local fence has passed */
    public int workerId() {
        int held = workerId;
        if (held < 0 || clock.getAsLong() >= validUntil) {
            throw new IllegalStateException("No order id worker lease is held");
        }
        return held;
    }

    public void start() {
        try {
            maintain();
        } catch (RuntimeException e) {
            log.warn("Order id worker lease not acquired at startup; retrying on schedule", e);
        }
    }

    @Scheduled(initialDelayString = "#{@orderProperties.workerLeaseTtl.toMillis() / 3}",
            fixedDelayString = "#{@orderProperties.workerLeaseTtl.toMillis() / 3}")
    public synchronized void maintain() {
        if (workerId >= 0) {
            long started = clock.getAsLong();
            Long renewed;
            try {
                renewed = redis.execute(RENEW, Collections.singletonList(KEY_PREFIX + workerId),
                        token, Long.toString(ttl.toMillis()));
            } catch (RuntimeException e) {
                // Keep the slot: the local fence decides when to stop issuing.
                log.warn("Order id worker lease renewal failed. workerId={}", workerId, e);
                return;
            }
            if (Long.valueOf(1L).equals(renewed)) {
                validUntil = fence(started);
                return;
            }
            log.error("Order id worker lease was lost. workerId={}", workerId);
            workerId = -1;
        }
        acquire();
    }

    private void acquire() {
        for (int id = 0; id < MAX_WORKERS; id++) {
            long started = clock.getAsLong();
            if (Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(KEY_PREFIX + id, token, ttl))) {
                validUntil = fence(started);
                workerId = id;
                log.info("Order id worker lease acquired. workerId={}", id);
                return;
            }
        }
        log.error("All {} order id worker slots are taken", MAX_WORKERS);
    }

    private long fence(long started) {
        return started + ttl.toMillis() - ttl.toMillis() / 6;
    }

    public synchronized void release() {
        int held = workerId;
        workerId = -1;
        if (held < 0) {
            return;
        }
        try {
            redis.execute(RELEASE, Collections.singletonList(KEY_PREFIX + held), token);
        } catch (RuntimeException e) {
            log.warn("Order id worker lease release failed; it expires by itself. workerId={}", held, e);
        }
    }
}
