package com.localdeals.trade.mq;

import com.localdeals.trade.config.OrderProperties;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.client.producer.SendStatus;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.stereotype.Component;

/** Sends the timer message that closes an unpaid order at its deadline. */
@Slf4j
@Component
public class OrderTimeoutScheduler {

    /**
     * The deadline is set by MySQL when the row is inserted, slightly after the caller's clock
     * reading here; delivering a second late avoids a NOT_DUE bounce through a broker retry.
     */
    private static final long DELIVERY_SLACK_MS = 1_000L;

    private final RocketMQTemplate rocketMQTemplate;
    private final OrderProperties orderProperties;

    public OrderTimeoutScheduler(RocketMQTemplate rocketMQTemplate, OrderProperties orderProperties) {
        this.rocketMQTemplate = rocketMQTemplate;
        this.orderProperties = orderProperties;
    }

    /** Best effort: returns false when the broker refused; the fallback scan closes the order later. */
    public boolean scheduleClose(long orderNo) {
        long deliverAt = System.currentTimeMillis() + orderProperties.getPayTimeout().toMillis() + DELIVERY_SLACK_MS;
        try {
            SendResult result = rocketMQTemplate.syncSendDeliverTimeMills(
                    orderProperties.getCloseTopic(), (Object) new OrderCloseMessage(orderNo), deliverAt);
            return result != null && result.getSendStatus() == SendStatus.SEND_OK;
        } catch (RuntimeException e) {
            log.warn("Order timeout message not sent; the fallback scan will close it. orderNo={}", orderNo, e);
            return false;
        }
    }
}
