package com.localdeals.trade.sharding;

import java.util.OptionalInt;

/**
 * Where an order row lives.
 *
 * <p>{@code trade_order} and its attached tables are split over {@link #DATABASES} databases of
 * {@link #TABLES_PER_DATABASE} tables each. A row's slot is {@code key % SLOTS}, and the slot
 * names both halves of the address: {@code database = slot % DATABASES},
 * {@code table = slot / DATABASES}, so consecutive users alternate databases instead of piling
 * into one.</p>
 *
 * <p>The point of the scheme is that the key can be either half of an order's identity. M3 put
 * {@code user_id % 1024} in the low ten bits of every order number, and 1024 is a multiple of
 * {@code SLOTS}, so {@code orderNo % SLOTS == userId % SLOTS}: a query that knows the user and a
 * query that only knows the order number reach the same single shard. The same holds for every
 * number derived from an order — {@code pay_no}, {@code refund_no}, a purchase's
 * {@code coupon_no} — which is what keeps a payment callback from becoming a broadcast.</p>
 *
 * <p>A coupon issued by a marketing grant is {@code G<grantId>} and carries no user gene. Its
 * slot is reported as empty rather than guessed: the caller broadcasts, which is correct and
 * rare, instead of reading one shard and missing the row.</p>
 */
public final class OrderShardSlots {

    /** Physical databases holding the sharded tables. */
    public static final int DATABASES = 2;
    /** Physical tables per database, e.g. {@code trade_order_0..3}. */
    public static final int TABLES_PER_DATABASE = 4;
    /** Addressable shards. Must divide the order number's 1024-value gene. */
    public static final int SLOTS = DATABASES * TABLES_PER_DATABASE;

    private static final String REFUND_OF_ORDER = "RU";
    private static final String REFUND_OF_PAYMENT = "RA";
    private static final char COUPON_OF_PURCHASE = 'P';

    private OrderShardSlots() {
    }

    /** The slot owning this user's orders, coupons, payments and refunds. */
    public static int ofUserId(long userId) {
        return (int) Math.floorMod(userId, (long) SLOTS);
    }

    /** The same slot, recovered from the order number's user gene. */
    public static int ofOrderNo(long orderNo) {
        return (int) Math.floorMod(orderNo, (long) SLOTS);
    }

    /** {@code pay_no} is {@code <orderNo>-<n>}. */
    public static OptionalInt ofPayNo(String payNo) {
        if (payNo == null) {
            return OptionalInt.empty();
        }
        int separator = payNo.lastIndexOf('-');
        return separator <= 0 ? OptionalInt.empty() : ofOrderNoText(payNo.substring(0, separator));
    }

    /** {@code refund_no} is {@code RU<orderNo>} for a user request, {@code RA<payNo>} otherwise. */
    public static OptionalInt ofRefundNo(String refundNo) {
        if (refundNo == null) {
            return OptionalInt.empty();
        }
        if (refundNo.startsWith(REFUND_OF_ORDER)) {
            return ofOrderNoText(refundNo.substring(REFUND_OF_ORDER.length()));
        }
        if (refundNo.startsWith(REFUND_OF_PAYMENT)) {
            return ofPayNo(refundNo.substring(REFUND_OF_PAYMENT.length()));
        }
        return OptionalInt.empty();
    }

    /** {@code coupon_no} is {@code P<orderNo>} for a purchase and {@code G<grantId>} for a grant. */
    public static OptionalInt ofCouponNo(String couponNo) {
        if (couponNo == null || couponNo.isEmpty() || couponNo.charAt(0) != COUPON_OF_PURCHASE) {
            return OptionalInt.empty();
        }
        return ofOrderNoText(couponNo.substring(1));
    }

    /** The database half of a slot's address. */
    public static int databaseOf(int slot) {
        return slot % DATABASES;
    }

    /** The table half of a slot's address. */
    public static int tableOf(int slot) {
        return slot / DATABASES;
    }

    private static OptionalInt ofOrderNoText(String text) {
        try {
            long orderNo = Long.parseLong(text);
            // Order numbers are issued above 2^57; anything else is not one, and guessing a slot
            // for it would send the query to a shard that cannot hold the row.
            return orderNo > 0 ? OptionalInt.of(ofOrderNo(orderNo)) : OptionalInt.empty();
        } catch (NumberFormatException e) {
            return OptionalInt.empty();
        }
    }
}
