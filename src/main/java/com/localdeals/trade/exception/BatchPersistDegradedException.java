package com.localdeals.trade.exception;

/**
 * Thrown to roll back a batch whose fast path no longer holds — an INSERT IGNORE skipped a row,
 * or the single per-voucher stock decrement did not apply. The caller then replays that group
 * one message at a time through the existing single-message classification.
 *
 * <p>The {@link Reason} is not decoration: a skipped INSERT means a redelivery met a row that
 * already existed, while a short stock means the batch outran what the voucher still has. They
 * are counted apart so a benchmark can tell one from the other.</p>
 */
public class BatchPersistDegradedException extends RuntimeException {

    public enum Reason {
        /** The multi-row {@code INSERT IGNORE} covered fewer rows than the batch had. */
        INSERT_SKIPPED,
        /** The one {@code stock - n} update did not apply: the voucher cannot cover the batch. */
        STOCK_SHORT
    }

    private final Reason reason;

    private BatchPersistDegradedException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public static BatchPersistDegradedException insertSkipped(String message) {
        return new BatchPersistDegradedException(Reason.INSERT_SKIPPED, message);
    }

    public static BatchPersistDegradedException stockShort(String message) {
        return new BatchPersistDegradedException(Reason.STOCK_SHORT, message);
    }

    public Reason getReason() {
        return reason;
    }
}
