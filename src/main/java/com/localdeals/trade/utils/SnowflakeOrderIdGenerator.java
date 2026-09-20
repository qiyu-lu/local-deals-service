package com.localdeals.trade.utils;

import java.time.Instant;
import java.util.function.IntSupplier;
import java.util.function.LongSupplier;

/**
 * Order numbers issued in-process, with no network round trip.
 *
 * <pre>
 *   0 | 41 bits ms since 2025-01-01Z | 5 bits worker | 7 bits sequence | 10 bits userId % 1024
 * </pre>
 *
 * <p>The low ten bits repeat the user's gene, so {@code order_no % 2^k == user_id % 2^k} for any
 * k <= 10: an order number routes to the same shard as its user. The epoch places every id at or
 * above 2^57 once 2^35 ms have elapsed (2026-02-02); every V1 Redis id is below 2^57, so the two
 * ranges never meet. A clock that would issue a lower id is treated as broken.</p>
 */
public class SnowflakeOrderIdGenerator {

    static final long EPOCH_MILLIS = Instant.parse("2025-01-01T00:00:00Z").toEpochMilli();
    static final int GENE_BITS = 10;
    static final int SEQUENCE_BITS = 7;
    static final int WORKER_BITS = 5;
    static final int MAX_WORKERS = 1 << WORKER_BITS;
    private static final long GENE_MASK = (1L << GENE_BITS) - 1;
    private static final long SEQUENCE_MASK = (1L << SEQUENCE_BITS) - 1;
    private static final int SEQUENCE_SHIFT = GENE_BITS;
    private static final int WORKER_SHIFT = GENE_BITS + SEQUENCE_BITS;
    private static final int TIME_SHIFT = GENE_BITS + SEQUENCE_BITS + WORKER_BITS;
    private static final long MIN_ELAPSED_MILLIS = 1L << (57 - TIME_SHIFT);
    private static final long MAX_ELAPSED_MILLIS = (1L << (63 - TIME_SHIFT)) - 1;
    /** An NTP step back up to this long is waited out; a larger one fails closed. */
    private static final long MAX_BACKWARD_MILLIS = 5;

    private final IntSupplier workerId;
    private final LongSupplier clock;
    private long lastMillis = -1;
    private long sequence;

    public SnowflakeOrderIdGenerator(IntSupplier workerId, LongSupplier clock) {
        this.workerId = workerId;
        this.clock = clock;
    }

    public long nextId(long userId) {
        int worker = workerId.getAsInt();
        if (worker < 0 || worker >= MAX_WORKERS) {
            throw new IllegalStateException("Order id worker is out of range: " + worker);
        }
        long millis;
        long seq;
        synchronized (this) {
            millis = clock.getAsLong();
            if (millis < lastMillis) {
                if (lastMillis - millis > MAX_BACKWARD_MILLIS) {
                    throw new IllegalStateException(
                            "Clock moved backwards by " + (lastMillis - millis) + " ms; refusing to issue order ids");
                }
                millis = waitUntil(lastMillis);
            }
            if (millis == lastMillis) {
                sequence = (sequence + 1) & SEQUENCE_MASK;
                if (sequence == 0) {
                    millis = waitUntil(lastMillis + 1);
                }
            } else {
                sequence = 0;
            }
            lastMillis = millis;
            seq = sequence;
        }
        long elapsed = millis - EPOCH_MILLIS;
        if (elapsed < MIN_ELAPSED_MILLIS || elapsed > MAX_ELAPSED_MILLIS) {
            throw new IllegalStateException("Clock is outside the order id range: " + Instant.ofEpochMilli(millis));
        }
        return elapsed << TIME_SHIFT
                | (long) worker << WORKER_SHIFT
                | seq << SEQUENCE_SHIFT
                | Math.floorMod(userId, 1L << GENE_BITS);
    }

    /** The {@code userId % 1024} embedded in an order number. */
    public static int geneOf(long orderNo) {
        return (int) (orderNo & GENE_MASK);
    }

    private long waitUntil(long target) {
        long now;
        while ((now = clock.getAsLong()) < target) {
            Thread.onSpinWait();
        }
        return now;
    }
}
