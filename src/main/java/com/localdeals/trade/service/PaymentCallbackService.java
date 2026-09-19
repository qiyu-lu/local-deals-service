package com.localdeals.trade.service;

import com.localdeals.trade.payment.PaymentNotification;
import com.localdeals.trade.payment.RefundNotification;
import org.springframework.stereotype.Service;

/** Handles the channel's signed, repeated and unordered callbacks. */
@Service
public class PaymentCallbackService {

    public enum PaidOutcome {
        /** This callback paid the order and issued its coupon. */
        PAID,
        /** The payment was already settled by an earlier copy of this callback. */
        DUPLICATE,
        /** The money arrived but the order was closed (or already paid): refunded automatically. */
        AUTO_REFUND,
        /** Amount mismatch: recorded as ABNORMAL for manual review, never applied. */
        ABNORMAL
    }

    public enum RefundOutcome {
        REFUNDED,
        DUPLICATE
    }

    public PaidOutcome onPaid(PaymentNotification notification) {
        throw new UnsupportedOperationException("M2: not implemented yet");
    }

    public RefundOutcome onRefunded(RefundNotification notification) {
        throw new UnsupportedOperationException("M2: not implemented yet");
    }
}
