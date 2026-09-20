package com.localdeals.trade.exception;

/**
 * Thrown to roll back a batch whose fast path no longer holds — an INSERT IGNORE skipped a row,
 * or the single per-voucher stock decrement did not apply. The caller then replays that group
 * one message at a time through the existing single-message classification.
 */
public class BatchPersistDegradedException extends RuntimeException {

    public BatchPersistDegradedException(String message) {
        super(message);
    }
}
