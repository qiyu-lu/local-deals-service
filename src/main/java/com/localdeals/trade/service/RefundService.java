package com.localdeals.trade.service;

import com.localdeals.trade.entity.RefundRecord;
import org.springframework.stereotype.Service;

/** User refunds of paid orders, automatic refunds of late payments, and their channel requests. */
@Service
public class RefundService {

    /** PAID -> REFUNDING with the coupon frozen; the channel confirms asynchronously. */
    public RefundRecord apply(long userId, long orderNo) {
        throw new UnsupportedOperationException("M2: not implemented yet");
    }

    /** Asks the channel again for refunds it has not confirmed; returns how many were requested. */
    public int retryStale(int limit) {
        throw new UnsupportedOperationException("M2: not implemented yet");
    }
}
