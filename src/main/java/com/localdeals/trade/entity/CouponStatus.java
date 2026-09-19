package com.localdeals.trade.entity;

public enum CouponStatus {
    AVAILABLE,
    USED,
    EXPIRED,
    /** Its purchase order was refunded. */
    REFUNDED,
    /** A refund of its purchase order is in flight; it cannot be verified meanwhile. */
    FROZEN
}
