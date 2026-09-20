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
 * L1 of the admission funnel: a JVM-local "sold out" flag per stock bucket. Once the Redis
 * script reports that a bucket has no stock, later requests of that bucket cost no network IO.
 *
 * <p>The interceptor that consults L1 runs before authentication, so it knows the voucher but
 * not the buyer, and therefore not the bucket. It may only answer "sold out" once <em>every</em>
 * bucket is flagged; until then a request has to reach Redis to find out whether its own bucket
 * still has stock.</p>
 *
 * <p>Flags are shared through Redis pub/sub ({@code +voucher:bucket} sets,
 * {@code -voucher:bucket} clears) and cleared whenever a unit goes back to a bucket (close,
 * refund, compensation). Pub/sub is fire-and-forget and messages from different instances can
 * cross, so a fully flagged voucher still lets one probe request per interval through to Redis:
 * a stale flag heals within that interval.</p>
 */
@Slf4j
public class SeckillSoldOutRegistry implements MessageListener {

    public static final String CHANNEL = "seckill:sold-out";

    private final StringRedisTemplate redis;
    private final long probeIntervalMillis;
    private final LongSupplier clock;
    private final SeckillBucketRouter router;
    /** voucherId -> the flags of its buckets */
    private final ConcurrentHashMap<Long, VoucherFlags> soldOut = new ConcurrentHashMap<>();

    public SeckillSoldOutRegistry(StringRedisTemplate redis, Duration probeInterval, LongSupplier clock,
                                  SeckillBucketRouter router) {
        this.redis = redis;
        this.probeIntervalMillis = probeInterval.toMillis();
        this.clock = clock;
        this.router = router;
    }

    /** @return true when the request can be answered "sold out" locally, without a user */
    public boolean rejectLocally(long voucherId) {
        VoucherFlags flags = soldOut.get(voucherId);
        if (flags == null || !flags.allBucketsFlagged()) {
            return false;
        }
        long now = clock.getAsLong();
        long due = flags.nextProbe.get();
        // Exactly one request per interval wins the CAS and goes on to Redis as the probe.
        return now < due || !flags.nextProbe.compareAndSet(due, now + probeIntervalMillis);
    }

    public boolean isSoldOut(long voucherId, int bucket) {
        VoucherFlags flags = soldOut.get(voucherId);
        return flags != null && flags.isFlagged(bucket);
    }

    public void markSoldOut(long voucherId, int bucket) {
        if (setLocally(voucherId, bucket)) {
            publish("+" + voucherId + ":" + bucket);
        }
    }

    public void clear(long voucherId, int bucket) {
        clearLocally(voucherId, bucket);
        publish("-" + voucherId + ":" + bucket);
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        String body = new String(message.getBody(), StandardCharsets.UTF_8);
        try {
            int separator = body.indexOf(':');
            long voucherId = Long.parseLong(body.substring(1, separator));
            int bucket = Integer.parseInt(body.substring(separator + 1));
            if (bucket < 0 || bucket >= router.count()) {
                throw new IllegalArgumentException("bucket out of range");
            }
            if (body.charAt(0) == '+') {
                setLocally(voucherId, bucket);
            } else if (body.charAt(0) == '-') {
                clearLocally(voucherId, bucket);
            }
        } catch (RuntimeException e) {
            log.warn("Ignoring malformed sold-out message: {}", body);
        }
    }

    private boolean setLocally(long voucherId, int bucket) {
        VoucherFlags flags = soldOut.computeIfAbsent(voucherId, id -> new VoucherFlags(router.count()));
        boolean changed = flags.flag(bucket);
        if (changed && flags.allBucketsFlagged()) {
            flags.nextProbe.set(clock.getAsLong() + probeIntervalMillis);
        }
        return changed;
    }

    private void clearLocally(long voucherId, int bucket) {
        VoucherFlags flags = soldOut.get(voucherId);
        if (flags != null && flags.unflag(bucket) && flags.noBucketFlagged()) {
            soldOut.remove(voucherId, flags);
        }
    }

    private void publish(String body) {
        try {
            redis.convertAndSend(CHANNEL, body);
        } catch (RuntimeException e) {
            // Other instances fall back to their own probe.
            log.warn("Sold-out broadcast failed: {}", body, e);
        }
    }

    /** The flags of one voucher's buckets, plus the probe window of the fully flagged voucher. */
    private static final class VoucherFlags {
        private final int bucketCount;
        private final boolean[] flagged;
        private final AtomicLong nextProbe = new AtomicLong();
        private int flaggedCount;

        VoucherFlags(int bucketCount) {
            this.bucketCount = bucketCount;
            this.flagged = new boolean[bucketCount];
        }

        synchronized boolean flag(int bucket) {
            if (bucket < 0 || bucket >= bucketCount || flagged[bucket]) {
                return false;
            }
            flagged[bucket] = true;
            flaggedCount++;
            return true;
        }

        synchronized boolean unflag(int bucket) {
            if (bucket < 0 || bucket >= bucketCount || !flagged[bucket]) {
                return false;
            }
            flagged[bucket] = false;
            flaggedCount--;
            return true;
        }

        synchronized boolean isFlagged(int bucket) {
            return bucket >= 0 && bucket < bucketCount && flagged[bucket];
        }

        synchronized boolean allBucketsFlagged() {
            return flaggedCount == bucketCount;
        }

        synchronized boolean noBucketFlagged() {
            return flaggedCount == 0;
        }
    }
}
