package com.localdeals.trade.payment;

/** Our view of a third-party payment channel. Results arrive later as signed callbacks. */
public interface PaymentChannel {

    String NAME_MOCK = "MOCK";

    /** Registers a payment and returns where the user pays it (idempotent per payNo). */
    String createPayment(String payNo, long amount);

    /** Asks for a refund; idempotent per refundNo. The result arrives as a {@link RefundNotification}. */
    void refund(String refundNo, String payNo, long amount);
}
