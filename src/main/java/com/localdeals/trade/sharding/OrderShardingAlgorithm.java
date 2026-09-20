package com.localdeals.trade.sharding;

import com.google.common.collect.Range;
import org.apache.shardingsphere.sharding.api.sharding.complex.ComplexKeysShardingAlgorithm;
import org.apache.shardingsphere.sharding.api.sharding.complex.ComplexKeysShardingValue;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;

/**
 * Routes a statement on an order table to the shards that can hold its rows.
 *
 * <p>Every column the strategy is configured with is read through {@link OrderShardSlots}, which
 * knows how to recover a slot from a user id, an order number, or any reference derived from one.
 * A value that carries no slot — a grant's {@code coupon_no}, a range predicate, a column nobody
 * declared — makes the whole statement a broadcast, because reading one shard for a row that may
 * live in another is a wrong answer while reading all of them is only a slow one.</p>
 */
abstract class OrderShardingAlgorithm implements ComplexKeysShardingAlgorithm<Comparable<?>> {

    static final String USER_ID = "user_id";
    static final String ORDER_NO = "order_no";
    static final String PAY_NO = "pay_no";
    static final String REFUND_NO = "refund_no";
    static final String COUPON_NO = "coupon_no";

    /** The part of a physical name this algorithm decides, e.g. {@code 1} of {@code ds_1}. */
    protected abstract int addressOf(int slot);

    @Override
    public Collection<String> doSharding(Collection<String> availableTargets,
                                         ComplexKeysShardingValue<Comparable<?>> shardingValue) {
        Set<Integer> slots = slotsOf(shardingValue);
        if (slots == null) {
            return availableTargets;
        }
        Set<String> targets = new LinkedHashSet<>();
        for (int slot : slots) {
            String suffix = "_" + addressOf(slot);
            for (String candidate : availableTargets) {
                if (candidate.endsWith(suffix)) {
                    targets.add(candidate);
                }
            }
        }
        // No candidate matched: the configured nodes and this scheme disagree. Broadcasting is
        // the only answer that cannot silently lose a row.
        return targets.isEmpty() ? availableTargets : targets;
    }

    /** The slots the statement can touch, or {@code null} when it must be broadcast. */
    private Set<Integer> slotsOf(ComplexKeysShardingValue<Comparable<?>> shardingValue) {
        Map<String, Range<Comparable<?>>> ranges = shardingValue.getColumnNameAndRangeValuesMap();
        if (ranges != null && !ranges.isEmpty()) {
            return null;
        }
        Map<String, Collection<Comparable<?>>> columns = shardingValue.getColumnNameAndShardingValuesMap();
        if (columns == null || columns.isEmpty()) {
            return null;
        }
        Set<Integer> slots = new LinkedHashSet<>();
        for (Map.Entry<String, Collection<Comparable<?>>> column : columns.entrySet()) {
            for (Comparable<?> value : column.getValue()) {
                OptionalInt slot = slotOf(column.getKey(), value);
                if (slot.isEmpty()) {
                    return null;
                }
                slots.add(slot.getAsInt());
            }
        }
        return slots.isEmpty() ? null : slots;
    }

    private static OptionalInt slotOf(String column, Comparable<?> value) {
        if (value == null) {
            return OptionalInt.empty();
        }
        String name = column.toLowerCase();
        return switch (name) {
            case USER_ID, ORDER_NO -> asLong(value);
            case PAY_NO -> OrderShardSlots.ofPayNo(value.toString());
            case REFUND_NO -> OrderShardSlots.ofRefundNo(value.toString());
            case COUPON_NO -> OrderShardSlots.ofCouponNo(value.toString());
            default -> OptionalInt.empty();
        };
    }

    /**
     * {@code user_id} and {@code order_no} are the same ten low bits, so one reading serves both.
     * A driver may hand the value over as any number type, or as its text.
     */
    private static OptionalInt asLong(Comparable<?> value) {
        if (value instanceof Number number) {
            return OptionalInt.of(OrderShardSlots.ofUserId(number.longValue()));
        }
        try {
            return OptionalInt.of(OrderShardSlots.ofUserId(Long.parseLong(value.toString())));
        } catch (NumberFormatException e) {
            return OptionalInt.empty();
        }
    }
}
