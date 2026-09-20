package com.localdeals.trade.service;

import com.localdeals.trade.config.SeckillProperties;
import org.springframework.stereotype.Component;

/**
 * Routes every seckill key to one of K stock buckets.
 *
 * <p>A voucher's stock, its reservations, the orders being processed and the rate-limit windows
 * are no longer one key each but K of them. The bucket is {@code userId % K}, so a user always
 * meets their own reservation field and the duplicate-purchase check stays atomic inside one
 * bucket without ever crossing to another. Order numbers repeat {@code userId % 1024} in their
 * low bits, so the same bucket can be recovered from an order number alone — which is what the
 * status endpoint has.</p>
 *
 * <p>Every key of one bucket carries the hash tag {@code {sk:b<n>}}, so all keys of a single Lua
 * call live in one Cluster slot. The tag deliberately does not name the voucher: during a
 * seckill one voucher is hot, and its K buckets already spread over K slots. Naming the voucher
 * would spread cold vouchers too, at the price of making a key unreachable without knowing its
 * voucher.</p>
 */
@Component
public class SeckillBucketRouter {

    /** The order number's gene is ten bits, so K larger than that could not be recovered. */
    private static final int MAX_BUCKETS = 1 << 10;

    private final int count;
    private final int mask;

    public SeckillBucketRouter(SeckillProperties seckillProperties) {
        this(seckillProperties.getBucket().getCount());
    }

    public SeckillBucketRouter(int count) {
        if (count < 1 || count > MAX_BUCKETS || Integer.bitCount(count) != 1) {
            throw new IllegalArgumentException(
                    "seckill bucket count must be a power of two between 1 and " + MAX_BUCKETS +
                            ", got " + count);
        }
        this.count = count;
        this.mask = count - 1;
    }

    public int count() {
        return count;
    }

    /** The bucket that owns this user's stock, reservation and rate-limit windows. */
    public int bucketOfUser(long userId) {
        return (int) (Math.floorMod(userId, (long) MAX_BUCKETS) & mask);
    }

    /** The same bucket, recovered from the order number's user gene. */
    public int bucketOfOrder(long orderNo) {
        return (int) (orderNo & mask);
    }

    public String stockKey(long voucherId, int bucket) {
        return prefix(bucket) + "stock:" + voucherId;
    }

    public String metaKey(long voucherId, int bucket) {
        return prefix(bucket) + "meta:" + voucherId;
    }

    public String reservationKey(long voucherId, int bucket) {
        return prefix(bucket) + "resv:" + voucherId;
    }

    /** The PROCESSING due-time index of a bucket; its members are order numbers. */
    public String processingKey(int bucket) {
        return prefix(bucket) + "processing";
    }

    public String statusKey(long orderNo, int bucket) {
        return prefix(bucket) + "status:" + orderNo;
    }

    /** The status key of an order, found without knowing its voucher. */
    public String statusKeyOfOrder(long orderNo) {
        return statusKey(orderNo, bucketOfOrder(orderNo));
    }

    /** Prefix of the per-user and per-IP rate-limit windows, inside the same slot. */
    public String trafficPrefix(long voucherId, int bucket) {
        return prefix(bucket) + "traffic:" + voucherId + ":";
    }

    /**
     * This bucket's share of a voucher's stock. The shares add up to the total and differ by at
     * most one unit, so no unit is lost and none is created.
     */
    public long stockShare(long totalStock, int bucket) {
        requireBucket(bucket);
        if (totalStock <= 0) {
            return 0;
        }
        return totalStock / count + (bucket < totalStock % count ? 1 : 0);
    }

    private String prefix(int bucket) {
        requireBucket(bucket);
        return "sk:{sk:b" + bucket + "}:";
    }

    private void requireBucket(int bucket) {
        if (bucket < 0 || bucket >= count) {
            throw new IllegalArgumentException("bucket " + bucket + " is outside 0.." + (count - 1));
        }
    }
}
