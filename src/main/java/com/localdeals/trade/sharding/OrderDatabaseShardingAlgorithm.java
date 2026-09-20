package com.localdeals.trade.sharding;

/** Picks the database of an order row: {@code ds_0} or {@code ds_1}. */
public final class OrderDatabaseShardingAlgorithm extends OrderShardingAlgorithm {

    public static final String TYPE = "LOCAL_DEALS_ORDER_DATABASE";

    @Override
    protected int addressOf(int slot) {
        return OrderShardSlots.databaseOf(slot);
    }

    @Override
    public String getType() {
        return TYPE;
    }
}
