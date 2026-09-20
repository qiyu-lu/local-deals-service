package com.localdeals.trade.service;

import com.localdeals.trade.utils.SnowflakeOrderIdGenerator;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The contracts M5 rests on: a user always lands in the same bucket, that bucket can be read
 * back from the order number alone, and every key one Lua call touches carries the same hash
 * tag, so the call stays inside one Cluster slot.
 */
class SeckillBucketRouterTest {

    private final SeckillBucketRouter router = new SeckillBucketRouter(16);

    @Test
    void bucketCountMustBeAPowerOfTwoWithinTheOrderIdGene() {
        assertThrows(IllegalArgumentException.class, () -> new SeckillBucketRouter(0));
        assertThrows(IllegalArgumentException.class, () -> new SeckillBucketRouter(3));
        assertThrows(IllegalArgumentException.class, () -> new SeckillBucketRouter(2048));
        assertEquals(1, new SeckillBucketRouter(1).count());
        assertEquals(1024, new SeckillBucketRouter(1024).count());
    }

    @Test
    void aUserAlwaysLandsInTheSameBucket() {
        for (long userId = 0; userId < 5_000; userId++) {
            assertEquals(userId % 16, router.bucketOfUser(userId));
            assertEquals(router.bucketOfUser(userId), router.bucketOfUser(userId));
        }
    }

    @Test
    void theOrderNumberCarriesTheSameBucketAsItsBuyer() {
        // The status of an order is looked up by order id alone (the polling endpoint has no
        // voucher), so the gene must be enough to find the bucket.
        AtomicLong clock = new AtomicLong(1_800_000_000_000L);
        SnowflakeOrderIdGenerator generator =
                new SnowflakeOrderIdGenerator(() -> 7, clock::getAndIncrement);
        for (long userId = 0; userId < 3_000; userId++) {
            long orderNo = generator.nextId(userId);
            assertEquals(router.bucketOfUser(userId), router.bucketOfOrder(orderNo),
                    "orderNo " + orderNo + " of user " + userId);
        }
    }

    @Test
    void everyKeyOfOneBucketSharesOneHashTag() {
        List<String> keys = List.of(
                router.stockKey(17L, 3),
                router.metaKey(17L, 3),
                router.reservationKey(17L, 3),
                router.processingKey(3),
                router.statusKeyOfOrder(geneOrder(3)),
                router.trafficPrefix(17L, 3) + "user:42:",
                router.trafficPrefix(17L, 3) + "ip:abc:");
        Set<String> tags = new HashSet<>();
        for (String key : keys) {
            tags.add(hashTag(key));
        }
        assertEquals(1, tags.size(), "keys " + keys + " must share one hash tag, got " + tags);
        assertTrue(tags.iterator().next().contains("3"), "the tag should name the bucket: " + tags);
    }

    @Test
    void differentBucketsOfTheSameVoucherGetDifferentTags() {
        Set<String> tags = new HashSet<>();
        for (int bucket = 0; bucket < router.count(); bucket++) {
            tags.add(hashTag(router.stockKey(17L, bucket)));
        }
        assertEquals(router.count(), tags.size());
    }

    @Test
    void stockIsSplitEvenlyAndCompletely() {
        for (long stock : new long[]{0, 1, 15, 16, 17, 1_000, 20_000}) {
            long sum = 0;
            long min = Long.MAX_VALUE;
            long max = Long.MIN_VALUE;
            for (int bucket = 0; bucket < router.count(); bucket++) {
                long share = router.stockShare(stock, bucket);
                assertTrue(share >= 0, "a bucket share is never negative");
                sum += share;
                min = Math.min(min, share);
                max = Math.max(max, share);
            }
            assertEquals(stock, sum, "the shares of " + stock + " must add up to it");
            assertTrue(max - min <= 1, "shares of " + stock + " differ by more than one unit");
        }
    }

    @Test
    void oneBucketBehavesLikeTheWholeVoucher() {
        SeckillBucketRouter single = new SeckillBucketRouter(1);
        assertEquals(0, single.bucketOfUser(1234L));
        assertEquals(20_000L, single.stockShare(20_000L, 0));
    }

    private static long geneOrder(int bucket) {
        return (1L << 20) | bucket;
    }

    private static String hashTag(String key) {
        int open = key.indexOf('{');
        int close = key.indexOf('}', open + 1);
        assertTrue(open >= 0 && close > open + 1, "key without a hash tag: " + key);
        return key.substring(open + 1, close);
    }
}
