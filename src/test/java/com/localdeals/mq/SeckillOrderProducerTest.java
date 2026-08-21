package com.localdeals.mq;

import cn.hutool.json.JSONUtil;
import com.localdeals.config.SeckillProperties;
import org.apache.rocketmq.spring.core.RocketMQLocalTransactionState;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

import static com.localdeals.mq.SeckillOrderProducer.ADMISSION_ACCEPTED;
import static com.localdeals.mq.SeckillOrderProducer.ADMISSION_NOT_STARTED;
import static com.localdeals.mq.SeckillOrderProducer.ADMISSION_SYSTEM_ERROR;
import static com.localdeals.utils.RedisConstants.SECKILL_META_KEY;
import static com.localdeals.utils.RedisConstants.SECKILL_ORDER_KEY;
import static com.localdeals.utils.RedisConstants.SECKILL_ORDER_STATUS_KEY;
import static com.localdeals.utils.RedisConstants.SECKILL_PROCESSING_INDEX_KEY;
import static com.localdeals.utils.RedisConstants.SECKILL_PROCESSING_QUARANTINE_KEY;
import static com.localdeals.utils.RedisConstants.SECKILL_RESERVATION_KEY;
import static com.localdeals.utils.RedisConstants.SECKILL_STOCK_KEY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
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
    @SuppressWarnings({"rawtypes", "unchecked"})
    void executeLocalTransaction_acceptsAndPassesAllAtomicKeysWithoutNarrowingOrderId() {
        when(stringRedisTemplate.execute(
                any(RedisScript.class), anyList(), any(), any(), any(), any()))
                .thenReturn((long) ADMISSION_ACCEPTED);
        SeckillOrderProducer.LocalTransactionContext context = context();

        RocketMQLocalTransactionState state = producer.executeLocalTransaction(message(), context);

        assertThat(state).isEqualTo(RocketMQLocalTransactionState.COMMIT);
        assertThat(context.getAdmissionResult()).isEqualTo(ADMISSION_ACCEPTED);

        ArgumentCaptor<List> keys = ArgumentCaptor.forClass(List.class);
        verify(stringRedisTemplate).execute(
                any(RedisScript.class), keys.capture(),
                eq(Long.toString(USER_ID)), eq(Long.toString(VOUCHER_ID)),
                eq(Long.toString(ORDER_ID)), eq("120"));
        assertThat(keys.getValue()).containsExactly(
                SECKILL_STOCK_KEY + VOUCHER_ID,
                SECKILL_ORDER_KEY + VOUCHER_ID,
                SECKILL_META_KEY + VOUCHER_ID,
                SECKILL_RESERVATION_KEY + VOUCHER_ID,
                SECKILL_ORDER_STATUS_KEY + ORDER_ID,
                SECKILL_PROCESSING_INDEX_KEY);
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void executeLocalTransaction_preservesNewBusinessRejectionCode() {
        when(stringRedisTemplate.execute(
                any(RedisScript.class), anyList(), any(), any(), any(), any()))
                .thenReturn((long) ADMISSION_NOT_STARTED);
        SeckillOrderProducer.LocalTransactionContext context = context();

        RocketMQLocalTransactionState state = producer.executeLocalTransaction(message(), context);

        assertThat(state).isEqualTo(RocketMQLocalTransactionState.ROLLBACK);
        assertThat(context.getAdmissionResult()).isEqualTo(ADMISSION_NOT_STARTED);
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void executeLocalTransaction_redisFailureReturnsUnknownAndSystemError() {
        when(stringRedisTemplate.execute(
                any(RedisScript.class), anyList(), any(), any(), any(), any()))
                .thenThrow(new RuntimeException("redis unavailable"));
        SeckillOrderProducer.LocalTransactionContext context = context();

        RocketMQLocalTransactionState state = producer.executeLocalTransaction(message(), context);

        assertThat(state).isEqualTo(RocketMQLocalTransactionState.UNKNOWN);
        assertThat(context.getAdmissionResult()).isEqualTo(ADMISSION_SYSTEM_ERROR);
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
    void sendSeckillTransaction_mqFailureReturnsSystemError() {
        when(rocketMQTemplate.sendMessageInTransaction(anyString(), any(Message.class), any()))
                .thenThrow(new RuntimeException("broker unavailable"));

        assertThat(producer.sendSeckillTransaction(VOUCHER_ID, USER_ID, ORDER_ID))
                .isEqualTo(ADMISSION_SYSTEM_ERROR);
    }

    @Test
    void sendSeckillTransaction_usesConfiguredIsolatedTopic() {
        SeckillProperties properties = new SeckillProperties();
        properties.setTopic("m5c-topic-run-1");
        ReflectionTestUtils.setField(producer, "seckillProperties", properties);

        producer.sendSeckillTransaction(VOUCHER_ID, USER_ID, ORDER_ID);

        verify(rocketMQTemplate).sendMessageInTransaction(
                eq("m5c-topic-run-1"), any(Message.class), any());
    }

    private static SeckillOrderProducer.LocalTransactionContext context() {
        return new SeckillOrderProducer.LocalTransactionContext(VOUCHER_ID, USER_ID, ORDER_ID);
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
