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
        // batch-size and thread-count are set above; pull-batch-size and pull-interval are not,
        // so these two carry the defaults the M6 sweep settled on.
        verify(pushConsumer).setPullBatchSize(256);
        // One connection per consume thread at most: a consumer must never wait for the pool.
        verify(pushConsumer).setConsumeThreadMin(16);
        verify(pushConsumer).setConsumeThreadMax(16);
        // A queue now waits 50 ms between pulls by default, which is what gives a batch something
        // to be a batch of: mean 2.16 orders at 0 ms, 12.68 at 50 ms, both at 2000 orders/s.
        verify(pushConsumer).setPullInterval(50L);
    }

    // The M5 smoke measured a mean batch of 1.03 with batchSize=64: the consumer keeps up with
    // the arrivals, so nothing is ever waiting in the queue when a thread pulls, and a batch of
    // one is all there is to hand over. A pull interval is what lets messages accumulate between
    // pulls; without it, consumeMessageBatchMaxSize can only ever be an upper bound nobody meets.
    @Test
    void prepareStart_aPullIntervalAndPullBatchSizeAreWhatLetABatchFill() {
        SeckillProperties properties = new SeckillProperties();
        properties.getConsume().setBatchSize(64);
        properties.getConsume().setThreadCount(4);
        properties.getConsume().setPullBatchSize(128);
        properties.getConsume().setPullInterval(java.time.Duration.ofMillis(20));
        SeckillOrderBatchConsumer consumer = new SeckillOrderBatchConsumer(
                properties, mock(SeckillOrderBatchProcessor.class), new ObjectMapper(),
                "localhost:9876");
        DefaultMQPushConsumer pushConsumer = mock(DefaultMQPushConsumer.class);

        consumer.prepareStart(pushConsumer);

        verify(pushConsumer).setPullBatchSize(128);
        verify(pushConsumer).setPullInterval(20L);
        verify(pushConsumer).setConsumeThreadMin(4);
        verify(pushConsumer).setConsumeThreadMax(4);
    }

    @Test
    void prepareStart_aPullBatchSmallerThanTheConsumeBatchIsRaisedToIt() {
        SeckillProperties properties = new SeckillProperties();
        properties.getConsume().setBatchSize(64);
        properties.getConsume().setPullBatchSize(16);
        SeckillOrderBatchConsumer consumer = new SeckillOrderBatchConsumer(
                properties, mock(SeckillOrderBatchProcessor.class), new ObjectMapper(),
                "localhost:9876");
        DefaultMQPushConsumer pushConsumer = mock(DefaultMQPushConsumer.class);

        consumer.prepareStart(pushConsumer);

        // A consume batch can never exceed what one pull brought in.
        verify(pushConsumer).setPullBatchSize(64);
    }
}
