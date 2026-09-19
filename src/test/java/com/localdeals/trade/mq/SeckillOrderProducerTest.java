package com.localdeals.trade.mq;

import cn.hutool.json.JSONUtil;
import com.localdeals.trade.config.SeckillProperties;
import org.apache.rocketmq.client.producer.LocalTransactionState;
import org.apache.rocketmq.client.producer.SendStatus;
import org.apache.rocketmq.client.producer.TransactionSendResult;
import org.apache.rocketmq.spring.core.RocketMQLocalTransactionState;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static com.localdeals.platform.utils.RedisConstants.SECKILL_ORDER_STATUS_KEY;
import static com.localdeals.platform.utils.RedisConstants.SECKILL_PROCESSING_QUARANTINE_KEY;
import static com.localdeals.platform.utils.RedisConstants.SECKILL_RESERVATION_KEY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Pure unit tests: RocketMQ and Redis are mocks; no Spring context or infrastructure is used. */
@ExtendWith(MockitoExtension.class)
class SeckillOrderProducerTest {

    private static final long VOUCHER_ID = 17L;
    private static final long USER_ID = 23L;
    private static final long ORDER_ID = 9007199254740993L;

    @Mock
    private RocketMQTemplate rocketMQTemplate;
    @Mock
    private StringRedisTemplate stringRedisTemplate;
    @Mock
    private HashOperations<String, Object, Object> hashOperations;
    @Mock
    private ZSetOperations<String, String> zSetOperations;

    private SeckillOrderProducer producer;

    @BeforeEach
    void setUp() {
        producer = new SeckillOrderProducer();
        ReflectionTestUtils.setField(producer, "rocketMQTemplate", rocketMQTemplate);
        ReflectionTestUtils.setField(producer, "stringRedisTemplate", stringRedisTemplate);
        ReflectionTestUtils.setField(producer, "seckillProperties", new SeckillProperties());
        org.mockito.Mockito.lenient().when(stringRedisTemplate.opsForZSet()).thenReturn(zSetOperations);
        org.mockito.Mockito.lenient().when(zSetOperations.score(
                SECKILL_PROCESSING_QUARANTINE_KEY, Long.toString(ORDER_ID))).thenReturn(null);
    }

    @Test
    void executeLocalTransaction_commitsWithoutTouchingRedisBecauseAdmissionAlreadyHappened() {
        RocketMQLocalTransactionState state = producer.executeLocalTransaction(message(), null);

        assertThat(state).isEqualTo(RocketMQLocalTransactionState.COMMIT);
        org.mockito.Mockito.verifyNoInteractions(stringRedisTemplate);
    }

    @Test
    void checkLocalTransaction_exactProcessingReservationCommits() {
        when(stringRedisTemplate.opsForHash()).thenReturn(hashOperations);
        when(hashOperations.get(SECKILL_RESERVATION_KEY + VOUCHER_ID, Long.toString(USER_ID)))
                .thenReturn(Long.toString(ORDER_ID));
        when(hashOperations.multiGet(
                SECKILL_ORDER_STATUS_KEY + ORDER_ID,
                Arrays.asList("status", "orderId", "userId", "voucherId")))
                .thenReturn(Arrays.asList(
                        "PROCESSING", Long.toString(ORDER_ID),
                        Long.toString(USER_ID), Long.toString(VOUCHER_ID)));

        assertThat(producer.checkLocalTransaction(brokerMessage()))
                .isEqualTo(RocketMQLocalTransactionState.COMMIT);
    }

    @Test
    void checkLocalTransaction_quarantinedOrderRollsBackBeforeReservationRead() {
        when(zSetOperations.score(SECKILL_PROCESSING_QUARANTINE_KEY, Long.toString(ORDER_ID)))
                .thenReturn(1770000000D);

        assertThat(producer.checkLocalTransaction(brokerMessage()))
                .isEqualTo(RocketMQLocalTransactionState.ROLLBACK);

        org.mockito.Mockito.verifyNoInteractions(hashOperations);
    }

    @Test
    void checkLocalTransaction_sameUserButDifferentOrderRollsBack() {
        when(stringRedisTemplate.opsForHash()).thenReturn(hashOperations);
        when(hashOperations.get(SECKILL_RESERVATION_KEY + VOUCHER_ID, Long.toString(USER_ID)))
                .thenReturn("777");

        assertThat(producer.checkLocalTransaction(brokerMessage()))
                .isEqualTo(RocketMQLocalTransactionState.ROLLBACK);
    }

    @Test
    void checkLocalTransaction_failedOrderRollsBack() {
        when(stringRedisTemplate.opsForHash()).thenReturn(hashOperations);
        when(hashOperations.get(SECKILL_RESERVATION_KEY + VOUCHER_ID, Long.toString(USER_ID)))
                .thenReturn(Long.toString(ORDER_ID));
        when(hashOperations.multiGet(
                SECKILL_ORDER_STATUS_KEY + ORDER_ID,
                Arrays.asList("status", "orderId", "userId", "voucherId")))
                .thenReturn(Arrays.asList(
                        "FAILED", Long.toString(ORDER_ID),
                        Long.toString(USER_ID), Long.toString(VOUCHER_ID)));

        assertThat(producer.checkLocalTransaction(brokerMessage()))
                .isEqualTo(RocketMQLocalTransactionState.ROLLBACK);
    }

    @Test
    void checkLocalTransaction_statusOwnedByDifferentOrderRollsBack() {
        when(stringRedisTemplate.opsForHash()).thenReturn(hashOperations);
        when(hashOperations.get(SECKILL_RESERVATION_KEY + VOUCHER_ID, Long.toString(USER_ID)))
                .thenReturn(Long.toString(ORDER_ID));
        when(hashOperations.multiGet(
                SECKILL_ORDER_STATUS_KEY + ORDER_ID,
                Arrays.asList("status", "orderId", "userId", "voucherId")))
                .thenReturn(Arrays.asList(
                        "PROCESSING", "777", Long.toString(USER_ID), Long.toString(VOUCHER_ID)));

        assertThat(producer.checkLocalTransaction(brokerMessage()))
                .isEqualTo(RocketMQLocalTransactionState.ROLLBACK);
    }

    @Test
    void checkLocalTransaction_redisFailureReturnsUnknown() {
        when(stringRedisTemplate.opsForHash()).thenReturn(hashOperations);
        when(hashOperations.get(SECKILL_RESERVATION_KEY + VOUCHER_ID, Long.toString(USER_ID)))
                .thenThrow(new RuntimeException("redis unavailable"));

        assertThat(producer.checkLocalTransaction(brokerMessage()))
                .isEqualTo(RocketMQLocalTransactionState.UNKNOWN);
    }

    @Test
    void publish_brokerFailureIsThrownToTheCaller() {
        when(rocketMQTemplate.sendMessageInTransaction(anyString(), any(Message.class), any()))
                .thenThrow(new RuntimeException("broker unavailable"));

        assertThatThrownBy(() -> producer.publish(new SeckillOrderMessage(VOUCHER_ID, USER_ID, ORDER_ID)))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void publish_aSendThatWasNotStoredIsAFailure() {
        when(rocketMQTemplate.sendMessageInTransaction(anyString(), any(Message.class), any()))
                .thenReturn(sendResult(SendStatus.FLUSH_DISK_TIMEOUT));

        assertThatThrownBy(() -> producer.publish(new SeckillOrderMessage(VOUCHER_ID, USER_ID, ORDER_ID)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void publish_usesConfiguredIsolatedTopic() {
        SeckillProperties properties = new SeckillProperties();
        properties.setTopic("m5c-topic-run-1");
        ReflectionTestUtils.setField(producer, "seckillProperties", properties);
        when(rocketMQTemplate.sendMessageInTransaction(eq("m5c-topic-run-1"), any(Message.class), any()))
                .thenReturn(sendResult(SendStatus.SEND_OK));

        producer.publish(new SeckillOrderMessage(VOUCHER_ID, USER_ID, ORDER_ID));

        verify(rocketMQTemplate).sendMessageInTransaction(
                eq("m5c-topic-run-1"), any(Message.class), any());
    }

    private static TransactionSendResult sendResult(SendStatus status) {
        TransactionSendResult result = new TransactionSendResult();
        result.setSendStatus(status);
        result.setLocalTransactionState(LocalTransactionState.COMMIT_MESSAGE);
        return result;
    }

    private static Message<SeckillOrderMessage> message() {
        return MessageBuilder.withPayload(
                new SeckillOrderMessage(VOUCHER_ID, USER_ID, ORDER_ID)).build();
    }

    private static Message<byte[]> brokerMessage() {
        byte[] payload = JSONUtil.toJsonStr(
                new SeckillOrderMessage(VOUCHER_ID, USER_ID, ORDER_ID))
                .getBytes(StandardCharsets.UTF_8);
        return MessageBuilder.withPayload(payload).build();
    }
}
