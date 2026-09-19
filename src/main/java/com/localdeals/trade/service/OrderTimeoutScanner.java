package com.localdeals.trade.service;

import org.springframework.stereotype.Component;

/** Fallback for lost timer messages and Redis releases that failed after commit. */
@Component
public class OrderTimeoutScanner {

    /** One bounded pass; returns how many orders it closed or released. */
    public int scanOnce() {
        throw new UnsupportedOperationException("M2: not implemented yet");
    }
}
