package com.localdeals.trade.mq;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.localdeals.trade.config.OrderProperties;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.producer.SendCallback;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.client.producer.SendStatus;
import org.apache.rocketmq.common.message.Message;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

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
    private final ObjectMapper objectMapper = new ObjectMapper();

    public OrderTimeoutScheduler(RocketMQTemplate rocketMQTemplate, OrderProperties orderProperties) {
        this.rocketMQTemplate = rocketMQTemplate;
        this.orderProperties = orderProperties;
    }

    /**
     * The batch consumer's form: hands the timer message to the producer's async sender instead
     * of waiting a broker round trip per order on the consume thread. Best effort in exactly the
     * same sense as {@link #scheduleClose}: if it never reaches the broker, OrderTimeoutScanner
     * closes the order later.
     */
    public void scheduleCloseAsync(long orderNo) {
        long deliverAt = System.currentTimeMillis() + orderProperties.getPayTimeout().toMillis() + DELIVERY_SLACK_MS;
        try {
            Message message = new Message(orderProperties.getCloseTopic(),
                    objectMapper.writeValueAsString(new OrderCloseMessage(orderNo))
                            .getBytes(StandardCharsets.UTF_8));
            message.setDeliverTimeMs(deliverAt);
            rocketMQTemplate.getProducer().send(message, new SendCallback() {
                @Override
                public void onSuccess(SendResult result) {
                    if (result == null || result.getSendStatus() != SendStatus.SEND_OK) {
                        log.warn("Order timeout message was not stored. orderNo={} status={}",
                                orderNo, result == null ? null : result.getSendStatus());
                    }
                }

                @Override
                public void onException(Throwable e) {
                    log.warn("Order timeout message not sent; the fallback scan will close it. orderNo={}",
                            orderNo, e);
                }
            });
        } catch (Exception e) {
            log.warn("Order timeout message not sent; the fallback scan will close it. orderNo={}", orderNo, e);
        }
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
