package com.localdeals.trade.mq;

import com.localdeals.trade.config.SeckillProperties;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.client.producer.SendStatus;
import org.apache.rocketmq.spring.core.RocketMQLocalTransactionListener;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.Message;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Pure unit tests: RocketMQ is a mock; no Spring context or infrastructure is used. */
@ExtendWith(MockitoExtension.class)
class SeckillOrderProducerTest {

    private static final long VOUCHER_ID = 17L;
    private static final long USER_ID = 23L;
    private static final long ORDER_ID = 9007199254740993L;

    @Mock
    private RocketMQTemplate rocketMQTemplate;

    private SeckillOrderProducer producer;

    @BeforeEach
    void setUp() {
        producer = new SeckillOrderProducer(rocketMQTemplate, new SeckillProperties());
    }

    /** Variant B: the Redis reservation is the outbox, so a plain send is enough. */
    @Test
    void publish_sendsOnePlainMessageAndNoHalfMessage() {
        when(rocketMQTemplate.syncSend(eq("seckill-order-topic"), any(Message.class)))
                .thenReturn(sendResult(SendStatus.SEND_OK));

        producer.publish(new SeckillOrderMessage(VOUCHER_ID, USER_ID, ORDER_ID));

        verify(rocketMQTemplate).syncSend(eq("seckill-order-topic"), any(Message.class));
        verify(rocketMQTemplate, never()).sendMessageInTransaction(anyString(), any(Message.class), any());
    }

    @Test
    void theProducerIsNoLongerATransactionListener() {
        assertThat(RocketMQLocalTransactionListener.class.isAssignableFrom(SeckillOrderProducer.class)).isFalse();
    }

    @Test
    void publish_brokerFailureIsThrownToTheCaller() {
        when(rocketMQTemplate.syncSend(anyString(), any(Message.class)))
                .thenThrow(new RuntimeException("broker unavailable"));

        assertThatThrownBy(() -> producer.publish(new SeckillOrderMessage(VOUCHER_ID, USER_ID, ORDER_ID)))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void publish_aSendThatWasNotStoredIsAFailure() {
        when(rocketMQTemplate.syncSend(anyString(), any(Message.class)))
                .thenReturn(sendResult(SendStatus.FLUSH_DISK_TIMEOUT));

        assertThatThrownBy(() -> producer.publish(new SeckillOrderMessage(VOUCHER_ID, USER_ID, ORDER_ID)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void publish_usesConfiguredIsolatedTopic() {
        SeckillProperties properties = new SeckillProperties();
        properties.setTopic("m5c-topic-run-1");
        producer = new SeckillOrderProducer(rocketMQTemplate, properties);
        when(rocketMQTemplate.syncSend(eq("m5c-topic-run-1"), any(Message.class)))
                .thenReturn(sendResult(SendStatus.SEND_OK));

        producer.publish(new SeckillOrderMessage(VOUCHER_ID, USER_ID, ORDER_ID));

        verify(rocketMQTemplate).syncSend(eq("m5c-topic-run-1"), any(Message.class));
    }

    private static SendResult sendResult(SendStatus status) {
        SendResult result = new SendResult();
        result.setSendStatus(status);
        return result;
    }
}
