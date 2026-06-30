package com.localdeals.exception;

/**
 * Thrown when DB stock reaches zero during seckill order creation.
 * This is a permanent business failure — retrying will not recover stock,
 * so the MQ consumer must ACK the message instead of triggering retry.
 */
public class StockExhaustedException extends RuntimeException {

    public StockExhaustedException(String message) {
        super(message);
    }
}
