package com.localdeals.exception;

/**
 * Signals that Redis admitted an order id which conflicts with an already persisted
 * purchase for the same user and voucher. Retrying the same message cannot repair this
 * divergence; the Redis reservation must be compensated and the voucher reconciled.
 */
public class OrderReservationConflictException extends RuntimeException {

    public OrderReservationConflictException(String message) {
        super(message);
    }
}
