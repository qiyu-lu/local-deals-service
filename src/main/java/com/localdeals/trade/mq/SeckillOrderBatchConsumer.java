package com.localdeals.trade.mq;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.localdeals.trade.config.SeckillProperties;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyStatus;
import org.apache.rocketmq.client.consumer.listener.MessageListenerConcurrently;
import org.apache.rocketmq.client.exception.MQClientException;
import org.apache.rocketmq.common.consumer.ConsumeFromWhere;
import org.apache.rocketmq.common.message.MessageExt;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Drains the seckill order topic in batches.
 *
 * <p>rocketmq-spring's listener container hands messages to a {@code RocketMQListener} one at a
 * time, so {@code consumeMessageBatchMaxSize} alone would not batch any of the work. This
 * consumer owns its {@link DefaultMQPushConsumer} and passes the whole batch to
 * {@link SeckillOrderBatchProcessor}, which is what turns N stock updates into one.</p>
 */
@Slf4j
@Component
@ConditionalOnProperty(
        prefix = "local-deals.seckill.consume",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true)
public class SeckillOrderBatchConsumer {

    private final SeckillProperties seckillProperties;
    private final SeckillOrderBatchProcessor batchProcessor;
    private final ObjectMapper objectMapper;
    private final String nameServer;
    private DefaultMQPushConsumer consumer;

    public SeckillOrderBatchConsumer(SeckillProperties seckillProperties,
                                     SeckillOrderBatchProcessor batchProcessor,
                                     ObjectMapper objectMapper,
                                     @Value("${rocketmq.name-server}") String nameServer) {
        this.seckillProperties = seckillProperties;
        this.batchProcessor = batchProcessor;
        this.objectMapper = objectMapper;
        this.nameServer = nameServer;
    }

    @PostConstruct
    public void start() throws MQClientException {
        SeckillProperties.Consume consume = seckillProperties.getConsume();
        DefaultMQPushConsumer pushConsumer =
                new DefaultMQPushConsumer(seckillProperties.getConsumerGroup());
        pushConsumer.setNamesrvAddr(nameServer);
        pushConsumer.subscribe(seckillProperties.getTopic(), "*");
        prepareStart(pushConsumer);
        pushConsumer.registerMessageListener((MessageListenerConcurrently) (messages, context) ->
                batchProcessor.process(parse(messages))
                        ? ConsumeConcurrentlyStatus.CONSUME_SUCCESS
                        : ConsumeConcurrentlyStatus.RECONSUME_LATER);
        pushConsumer.start();
        this.consumer = pushConsumer;
        log.info("Seckill batch consumer started. topic={} batchSize={} threads={} pullBatch={} pullInterval={}ms",
                seckillProperties.getTopic(), consume.getBatchSize(), consume.getThreadCount(),
                consume.getPullBatchSize(), consume.getPullInterval().toMillis());
    }

    /** Visible for testing: everything about the push consumer except the broker connection. */
    void prepareStart(DefaultMQPushConsumer pushConsumer) {
        SeckillProperties.Consume consume = seckillProperties.getConsume();
        // A brand-new deployment may start before the topic is provisioned. If orders are
        // committed while the consumer has no offset yet, RocketMQ's LAST_OFFSET default can
        // skip those first messages when the topic later becomes visible. Existing offsets are
        // still authoritative; this only changes the no-offset bootstrap position.
        pushConsumer.setConsumeFromWhere(ConsumeFromWhere.CONSUME_FROM_FIRST_OFFSET);
        pushConsumer.setConsumeMessageBatchMaxSize(consume.getBatchSize());
        // Without a pull batch at least as large, the consume batch can never be filled.
        pushConsumer.setPullBatchSize(Math.max(consume.getPullBatchSize(), consume.getBatchSize()));
        // Zero keeps the M4 behaviour: pull as fast as the queue allows. The M5 smoke showed
        // what that costs when the consumer keeps up — a mean batch of 1.03 against a maximum
        // of 64 — so this is the knob a throughput sweep turns.
        pushConsumer.setPullInterval(consume.getPullInterval().toMillis());
        pushConsumer.setConsumeThreadMin(consume.getThreadCount());
        pushConsumer.setConsumeThreadMax(consume.getThreadCount());
    }

    @PreDestroy
    public void stop() {
        if (consumer != null) {
            consumer.shutdown();
            consumer = null;
        }
    }

    private List<SeckillOrderMessage> parse(List<MessageExt> messages) {
        List<SeckillOrderMessage> parsed = new ArrayList<>(messages.size());
        for (MessageExt message : messages) {
            try {
                parsed.add(objectMapper.readValue(
                        new String(message.getBody(), StandardCharsets.UTF_8),
                        SeckillOrderMessage.class));
            } catch (Exception e) {
                // A body this consumer cannot read is not retryable; the processor counts it.
                log.error("Unreadable seckill message body. msgId={}", message.getMsgId(), e);
                parsed.add(null);
            }
        }
        return parsed;
    }
}
