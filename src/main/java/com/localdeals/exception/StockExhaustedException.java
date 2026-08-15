package com.localdeals.exception;

/**
 * Thrown when DB stock reaches zero during seckill order creation.
 * This is a permanent DB business failure. The MQ consumer acknowledges it only after
 * the corresponding Redis reservation has been compensated; compensation outages retry.
 */
public class StockExhaustedException extends RuntimeException {

    public StockExhaustedException(String message) {
        super(message);
    }
}
