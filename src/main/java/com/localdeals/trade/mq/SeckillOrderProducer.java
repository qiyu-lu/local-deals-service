package com.localdeals.trade.mq;

import com.localdeals.trade.config.SeckillProperties;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.client.producer.SendStatus;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Service;

/**
 * Publishes an admitted seckill order with one plain synchronous send.
 *
 * <p>No transaction message: the Redis PROCESSING reservation written by the admission Lua is
 * already the outbox. If this send fails, or the process dies between the Lua and the send, the
 * reconciler finds the reservation without a DB order and publishes it again.</p>
 */
@Service
public class SeckillOrderProducer {

    private final RocketMQTemplate rocketMQTemplate;
    private final SeckillProperties seckillProperties;

    public SeckillOrderProducer(RocketMQTemplate rocketMQTemplate, SeckillProperties seckillProperties) {
        this.rocketMQTemplate = rocketMQTemplate;
        this.seckillProperties = seckillProperties;
    }

    /** @throws RuntimeException when the broker did not store the message */
    public void publish(SeckillOrderMessage message) {
        SendResult result = rocketMQTemplate.syncSend(
                seckillProperties.getTopic(), MessageBuilder.withPayload(message).build());
        if (result == null || result.getSendStatus() != SendStatus.SEND_OK) {
            throw new IllegalStateException("Seckill order message was not stored. orderId=" +
                    message.getOrderId() + ", status=" + (result == null ? null : result.getSendStatus()));
        }
    }
}
