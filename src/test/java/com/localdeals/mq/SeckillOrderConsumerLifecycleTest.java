package com.localdeals.mq;

import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.apache.rocketmq.common.consumer.ConsumeFromWhere;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class SeckillOrderConsumerLifecycleTest {

    @Test
    void prepareStart_newConsumerStartsAtFirstOffset() {
        SeckillOrderConsumer consumer = new SeckillOrderConsumer();
        DefaultMQPushConsumer pushConsumer = mock(DefaultMQPushConsumer.class);

        consumer.prepareStart(pushConsumer);

        verify(pushConsumer).setConsumeFromWhere(ConsumeFromWhere.CONSUME_FROM_FIRST_OFFSET);
    }
}
