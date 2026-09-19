package com.localdeals.trade.service;

import lombok.AllArgsConstructor;
import lombok.Data;
import org.springframework.stereotype.Service;

/** Prepay: turns a PENDING_PAY order into a channel payment the user can complete. */
@Service
public class PaymentService {

    @Data
    @AllArgsConstructor
    public static class Prepay {
        private String payNo;
        private long amount;
        private String cashierUrl;
    }

    public Prepay prepay(long userId, long orderNo) {
        throw new UnsupportedOperationException("M2: not implemented yet");
    }
}
