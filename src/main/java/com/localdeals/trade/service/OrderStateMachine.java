package com.localdeals.trade.service;

import com.localdeals.trade.entity.OrderEvent;
import com.localdeals.trade.entity.OrderStateLog;
import com.localdeals.trade.entity.OrderStatus;
import com.localdeals.trade.mapper.OrderStateLogMapper;
import com.localdeals.trade.mapper.TradeOrderMapper;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owns every {@code trade_order.status} change. A transition is one compare-and-set UPDATE on
 * the event's source status; when two events race (payment callback vs timeout close, two
 * refund requests, ...) InnoDB serialises them on the row and exactly one sees an affected row.
 *
 * <p>Callers must already be in a transaction: the side effects of a winning transition (stock
 * back, coupon issued or frozen, refund row) have to commit or roll back with it.</p>
 */
@Component
public class OrderStateMachine {

    private final TradeOrderMapper tradeOrderMapper;
    private final OrderStateLogMapper stateLogMapper;

    public OrderStateMachine(TradeOrderMapper tradeOrderMapper, OrderStateLogMapper stateLogMapper) {
        this.tradeOrderMapper = tradeOrderMapper;
        this.stateLogMapper = stateLogMapper;
    }

    /** @return true when this call moved the order; false when the order was not in the source status. */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean fire(long orderNo, OrderEvent event, String operator) {
        int rows = tradeOrderMapper.transition(orderNo, event.from().name(), event.to().name(),
                event.requiresExpiry(), event.releasesInventory());
        if (rows == 0) {
            return false;
        }
        stateLogMapper.insert(new OrderStateLog(orderNo, event.from(), event.to(), event.name(), operator));
        return true;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void recordCreated(long orderNo, String operator) {
        stateLogMapper.insert(new OrderStateLog(orderNo, null, OrderStatus.PENDING_PAY,
                OrderStateLog.EVENT_CREATE, operator));
    }
}
