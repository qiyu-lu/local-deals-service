package com.localdeals.trade.service;

import com.localdeals.trade.entity.OrderEvent;
import com.localdeals.trade.mapper.OrderStateLogMapper;
import com.localdeals.trade.mapper.TradeOrderMapper;
import org.springframework.stereotype.Component;

/** Owns every {@code trade_order.status} change. */
@Component
public class OrderStateMachine {

    private final TradeOrderMapper tradeOrderMapper;
    private final OrderStateLogMapper stateLogMapper;

    public OrderStateMachine(TradeOrderMapper tradeOrderMapper, OrderStateLogMapper stateLogMapper) {
        this.tradeOrderMapper = tradeOrderMapper;
        this.stateLogMapper = stateLogMapper;
    }

    public boolean fire(long orderNo, OrderEvent event, String operator) {
        throw new UnsupportedOperationException("M2: not implemented yet");
    }

    public void recordCreated(long orderNo, String operator) {
        throw new UnsupportedOperationException("M2: not implemented yet");
    }
}
