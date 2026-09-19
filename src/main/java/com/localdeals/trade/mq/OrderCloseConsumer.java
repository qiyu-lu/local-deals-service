package com.localdeals.trade.mq;

import com.localdeals.trade.service.OrderCloseService;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.stereotype.Service;

/** Receives the timer message at the order's deadline. */
@Slf4j
@Service
@RocketMQMessageListener(
        topic = "${local-deals.order.close-topic:order-close-topic}",
        consumerGroup = "${local-deals.order.close-consumer-group:order-close-consumer-group}")
public class OrderCloseConsumer implements RocketMQListener<OrderCloseMessage> {

    private final OrderCloseService orderCloseService;

    public OrderCloseConsumer(OrderCloseService orderCloseService) {
        this.orderCloseService = orderCloseService;
    }

    @Override
    public void onMessage(OrderCloseMessage message) {
        if (message == null || message.getOrderNo() == null) {
            log.error("Dropping malformed order close message: {}", message);
            return;
        }
        OrderCloseService.Outcome outcome = orderCloseService.closeIfExpired(message.getOrderNo(), "SYSTEM");
        if (outcome == OrderCloseService.Outcome.NOT_DUE) {
            // Broker and database clocks disagree; the broker's retry backoff brings it back.
            throw new IllegalStateException("Order is not due yet. orderNo=" + message.getOrderNo());
        }
    }
}
