package com.localdeals.trade.utils;

import java.util.function.IntSupplier;
import java.util.function.LongSupplier;

/** Stub for the red commit. */
public class SnowflakeOrderIdGenerator {

    public SnowflakeOrderIdGenerator(IntSupplier workerId, LongSupplier clock) {
    }

    public long nextId(long userId) {
        throw new UnsupportedOperationException("not implemented");
    }

    public static int geneOf(long orderNo) {
        throw new UnsupportedOperationException("not implemented");
    }
}
