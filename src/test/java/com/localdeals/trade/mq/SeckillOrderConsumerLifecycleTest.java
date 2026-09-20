package com.localdeals.trade.mq;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.localdeals.trade.config.SeckillProperties;
import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.apache.rocketmq.common.consumer.ConsumeFromWhere;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class SeckillOrderConsumerLifecycleTest {

    @Test
    void prepareStart_newConsumerStartsAtFirstOffsetAndConsumesInBatches() {
        SeckillProperties properties = new SeckillProperties();
        properties.getConsume().setBatchSize(64);
        properties.getConsume().setThreadCount(16);
        SeckillOrderBatchConsumer consumer = new SeckillOrderBatchConsumer(
                properties, mock(SeckillOrderBatchProcessor.class), new ObjectMapper(),
                "localhost:9876");
        DefaultMQPushConsumer pushConsumer = mock(DefaultMQPushConsumer.class);

        consumer.prepareStart(pushConsumer);

        verify(pushConsumer).setConsumeFromWhere(ConsumeFromWhere.CONSUME_FROM_FIRST_OFFSET);
        verify(pushConsumer).setConsumeMessageBatchMaxSize(64);
        verify(pushConsumer).setPullBatchSize(64);
        // One connection per consume thread at most: a consumer must never wait for the pool.
        verify(pushConsumer).setConsumeThreadMin(16);
        verify(pushConsumer).setConsumeThreadMax(16);
    }
}
