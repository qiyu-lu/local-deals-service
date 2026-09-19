package com.localdeals.trade.mq;

import com.localdeals.trade.service.OrderCloseService;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.stereotype.Service;

/** Receives the timer message at the order's deadline. */
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
        throw new UnsupportedOperationException("M2: not implemented yet");
    }
}
