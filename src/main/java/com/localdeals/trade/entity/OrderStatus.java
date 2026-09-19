package com.localdeals.trade.entity;

/** Lifecycle of a {@code trade_order}. Every change goes through {@code OrderStateMachine}. */
public enum OrderStatus {
    PENDING_PAY,
    PAID,
    USED,
    CLOSED,
    REFUNDING,
    REFUNDED
}
