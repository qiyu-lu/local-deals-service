package com.localdeals.trade.service;

import com.localdeals.trade.entity.OrderEvent;
import com.localdeals.trade.entity.OrderStatus;
import com.localdeals.trade.entity.TradeOrder;
import com.localdeals.trade.mapper.TradeOrderMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.EnumMap;
import java.util.Map;

/**
 * Closes an unpaid order after its deadline and returns its stock exactly once. Called by the
 * timer-message consumer and the fallback scan; any number of concurrent or repeated calls are
 * safe because only the caller that wins the PENDING_PAY -> CLOSED compare-and-set restores stock.
 */
@Slf4j
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

    private final OrderStateMachine stateMachine;
    private final TradeOrderMapper tradeOrderMapper;
    private final ReservationReleaseService releaseService;
    private final Map<Outcome, Counter> counters = new EnumMap<>(Outcome.class);

    public OrderCloseService(OrderStateMachine stateMachine, TradeOrderMapper tradeOrderMapper,
                             ReservationReleaseService releaseService, MeterRegistry meterRegistry) {
        this.stateMachine = stateMachine;
        this.tradeOrderMapper = tradeOrderMapper;
        this.releaseService = releaseService;
        for (Outcome outcome : Outcome.values()) {
            counters.put(outcome, Counter.builder("local_deals.order.close")
                    .tag("result", outcome.name().toLowerCase()).register(meterRegistry));
        }
    }

    @Transactional
    public Outcome closeIfExpired(long orderNo, String operator) {
        Outcome outcome = close(orderNo, operator);
        counters.get(outcome).increment();
        return outcome;
    }

    private Outcome close(long orderNo, String operator) {
        if (stateMachine.fire(orderNo, OrderEvent.CLOSE, operator)) {
            TradeOrder order = tradeOrderMapper.selectById(orderNo);
            releaseService.returnUnit(order);
            log.info("Unpaid order closed. orderNo={} operator={}", orderNo, operator);
            return Outcome.CLOSED;
        }
        TradeOrder order = tradeOrderMapper.selectById(orderNo);
        if (order == null) {
            return Outcome.NOT_FOUND;
        }
        if (order.getStatus() == OrderStatus.CLOSED) {
            // A redelivered message is a second chance for a release that failed after commit.
            if (Integer.valueOf(1).equals(order.getReleasePending())) {
                releaseService.releaseAfterCommit(order);
            }
            return Outcome.ALREADY_CLOSED;
        }
        return order.getStatus() == OrderStatus.PENDING_PAY ? Outcome.NOT_DUE : Outcome.NOT_PENDING;
    }
}
