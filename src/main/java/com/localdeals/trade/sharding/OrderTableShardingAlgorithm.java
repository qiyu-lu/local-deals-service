package com.localdeals.trade.sharding;

/** Picks the table of an order row inside its database, e.g. {@code trade_order_2}. */
public final class OrderTableShardingAlgorithm extends OrderShardingAlgorithm {

    public static final String TYPE = "LOCAL_DEALS_ORDER_TABLE";

    @Override
    protected int addressOf(int slot) {
        return OrderShardSlots.tableOf(slot);
    }

    @Override
    public String getType() {
        return TYPE;
    }
}
