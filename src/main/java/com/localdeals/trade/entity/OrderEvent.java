package com.localdeals.trade.entity;

/**
 * The only legal order transitions. Each event has exactly one source status, so a transition is
 * a single compare-and-set {@code UPDATE ... WHERE order_no=? AND status=from}; zero affected
 * rows means another event won the race.
 */
public enum OrderEvent {
    /** Payment callback confirmed the money. */
    PAY(OrderStatus.PENDING_PAY, OrderStatus.PAID, false, false),
    /** Timeout close; only legal once {@code expire_at} has passed (checked by the database clock). */
    CLOSE(OrderStatus.PENDING_PAY, OrderStatus.CLOSED, true, true),
    /** Merchant verified the purchased coupon at the shop. */
    VERIFY(OrderStatus.PAID, OrderStatus.USED, false, false),
    /** User asked for a refund; the coupon is frozen in the same transaction. */
    REFUND_APPLY(OrderStatus.PAID, OrderStatus.REFUNDING, false, false),
    /** Channel confirmed the refund. */
    REFUND_SUCCESS(OrderStatus.REFUNDING, OrderStatus.REFUNDED, false, true);

    private final OrderStatus from;
    private final OrderStatus to;
    private final boolean requiresExpiry;
    private final boolean releasesInventory;

    OrderEvent(OrderStatus from, OrderStatus to, boolean requiresExpiry, boolean releasesInventory) {
        this.from = from;
        this.to = to;
        this.requiresExpiry = requiresExpiry;
        this.releasesInventory = releasesInventory;
    }

    public OrderStatus from() {
        return from;
    }

    public OrderStatus to() {
        return to;
    }

    public boolean requiresExpiry() {
        return requiresExpiry;
    }

    /** The order gives its unit of stock back (DB in the same transaction, Redis after commit). */
    public boolean releasesInventory() {
        return releasesInventory;
    }
}
