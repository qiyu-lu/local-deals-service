package com.localdeals.trade.service;

import org.springframework.stereotype.Service;

/** Closes an unpaid order after its deadline and returns its stock exactly once. */
@Service
public class OrderCloseService {

    public enum Outcome {
        /** This call closed the order. */
        CLOSED,
        /** Already closed by an earlier message or the scan. */
        ALREADY_CLOSED,
        /** Paid, used or refunded: the payment won, nothing to do. */
        NOT_PENDING,
        /** Still before expire_at by the database clock; retry later. */
        NOT_DUE,
        NOT_FOUND
    }

    public Outcome closeIfExpired(long orderNo, String operator) {
        throw new UnsupportedOperationException("M2: not implemented yet");
    }
}
