package com.localdeals.trade.mq;

import com.localdeals.trade.config.OrderProperties;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.apache.rocketmq.client.producer.SendCallback;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.client.producer.SendStatus;
import org.apache.rocketmq.common.message.Message;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OrderTimeoutSchedulerTest {

    private final RocketMQTemplate template = mock(RocketMQTemplate.class);
    private final OrderProperties properties = new OrderProperties();
    private final OrderTimeoutScheduler scheduler = new OrderTimeoutScheduler(template, properties);

    @Test
    void theCloseIsDeliveredJustAfterTheDatabaseDeadline() {
        properties.setPayTimeout(Duration.ofMinutes(15));
        SendResult ok = new SendResult();
        ok.setSendStatus(SendStatus.SEND_OK);
        when(template.syncSendDeliverTimeMills(eq("order-close-topic"), any(Object.class), anyLong()))
                .thenReturn(ok);
        long before = System.currentTimeMillis();

        assertThat(scheduler.scheduleClose(77L)).isTrue();

        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        ArgumentCaptor<Long> deliverAt = ArgumentCaptor.forClass(Long.class);
        verify(template).syncSendDeliverTimeMills(eq("order-close-topic"), payload.capture(), deliverAt.capture());
        assertThat(((OrderCloseMessage) payload.getValue()).getOrderNo()).isEqualTo(77L);
        // The deadline is computed by MySQL after this call; a small slack keeps the message
        // from arriving before it and bouncing through a broker retry.
        assertThat(deliverAt.getValue()).isBetween(before + Duration.ofMinutes(15).toMillis(),
                System.currentTimeMillis() + Duration.ofMinutes(15).plusSeconds(2).toMillis());
    }

    @Test
    void aBrokerFailureIsReportedNotThrown() {
        when(template.syncSendDeliverTimeMills(any(String.class), any(Object.class), anyLong()))
                .thenThrow(new IllegalStateException("no route"));

        assertThat(scheduler.scheduleClose(78L)).isFalse();
    }

    @Test
    void aBatchSchedulesItsClosesWithoutWaitingForTheBroker() throws Exception {
        properties.setPayTimeout(Duration.ofMinutes(15));
        DefaultMQProducer producer = mock(DefaultMQProducer.class);
        when(template.getProducer()).thenReturn(producer);
        long before = System.currentTimeMillis();

        scheduler.scheduleCloseAsync(77L);

        ArgumentCaptor<Message> sent = ArgumentCaptor.forClass(Message.class);
        verify(producer).send(sent.capture(), any(SendCallback.class));
        // A timer message carries its delivery time on the message itself.
        assertThat(sent.getValue().getTopic()).isEqualTo("order-close-topic");
        assertThat(sent.getValue().getDeliverTimeMs())
                .isBetween(before + Duration.ofMinutes(15).toMillis(),
                        System.currentTimeMillis() + Duration.ofMinutes(15).toMillis() + 2000);
        assertThat(new String(sent.getValue().getBody(), java.nio.charset.StandardCharsets.UTF_8))
                .contains("77");
        verify(template, org.mockito.Mockito.never())
                .syncSendDeliverTimeMills(any(String.class), any(Object.class), anyLong());
    }

    @Test
    void aBrokerThatRefusesTheTimerMessageNeverBreaksTheBatch() throws Exception {
        properties.setPayTimeout(Duration.ofMinutes(15));
        DefaultMQProducer producer = mock(DefaultMQProducer.class);
        when(template.getProducer()).thenReturn(producer);
        org.mockito.Mockito.doThrow(new IllegalStateException("broker down"))
                .when(producer).send(any(Message.class), any(SendCallback.class));

        // The order is committed; OrderTimeoutScanner closes it if no timer message exists.
        scheduler.scheduleCloseAsync(77L);
    }
}
