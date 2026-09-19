package com.localdeals.trade.payment;

import com.localdeals.trade.config.PaymentProperties;

/**
 * In-process stand-in for a payment channel. Like a real one it answers asynchronously, signs
 * its callbacks, delivers each at least once (here: {@code duplicates} times), retries on
 * failure with backoff, and makes no ordering promise.
 */
public class MockPaymentChannel implements PaymentChannel {

    /** How the channel reaches us; HTTP in the application, a fake in unit tests. */
    public interface Transport {
        /** @return true when we acknowledged (HTTP 2xx). */
        boolean deliverPayment(PaymentNotification notification);

        boolean deliverRefund(RefundNotification notification);
    }

    private final PaymentSigner signer;
    private final Transport transport;
    private final PaymentProperties.MockChannel settings;

    public MockPaymentChannel(PaymentSigner signer, Transport transport, PaymentProperties.MockChannel settings) {
        this.signer = signer;
        this.transport = transport;
        this.settings = settings;
    }

    @Override
    public String createPayment(String payNo, long amount) {
        throw new UnsupportedOperationException("M2: not implemented yet");
    }

    /** The user pays at the channel's cashier. Returns the channel transaction number. */
    public String pay(String payNo) {
        throw new UnsupportedOperationException("M2: not implemented yet");
    }

    @Override
    public void refund(String refundNo, String payNo, long amount) {
        throw new UnsupportedOperationException("M2: not implemented yet");
    }

    public void shutdown() {
    }
}
