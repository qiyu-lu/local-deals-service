package com.localdeals.exception;

/**
 * Signals that a generated seckill order id is already owned by another durable DB order.
 * Ownership cannot be proved safe enough for automatic Redis stock compensation.
 */
public class OrderIdConflictException extends RuntimeException {

    public OrderIdConflictException(String message) {
        super(message);
    }
}
