package com.localdeals.service;

import com.localdeals.mq.SeckillOrderMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import static com.localdeals.utils.RedisConstants.SECKILL_ORDER_KEY;
import static com.localdeals.utils.RedisConstants.SECKILL_ORDER_STATUS_KEY;
import static com.localdeals.utils.RedisConstants.SECKILL_ORDER_STATUS_TTL_SECONDS;
import static com.localdeals.utils.RedisConstants.SECKILL_RESERVATION_KEY;
import static com.localdeals.utils.RedisConstants.SECKILL_STOCK_KEY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SeckillOrderStateServiceTest {

    private StringRedisTemplate redisTemplate;
    private HashOperations<String, Object, Object> hashOperations;
    private SeckillOrderStateService service;
    private SeckillOrderMessage message;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        redisTemplate = mock(StringRedisTemplate.class);
        hashOperations = mock(HashOperations.class);
        when(redisTemplate.opsForHash()).thenReturn(hashOperations);
        service = new SeckillOrderStateService(redisTemplate);
        message = new SeckillOrderMessage(17L, 23L, 90071992547409931L);
    }

    @Test
    void markSuccessUsesExactReservationAndStringOrderId() {
        doReturn(1L).when(redisTemplate).execute(
                any(RedisScript.class), anyList(), any(), any(), any(), any());

        assertThat(service.markSuccess(message)).isTrue();

        verify(redisTemplate).execute(
                any(RedisScript.class),
                eq(Arrays.asList(
                        SECKILL_ORDER_STATUS_KEY + message.getOrderId(),
                        SECKILL_RESERVATION_KEY + message.getVoucherId())),
                eq(message.getUserId().toString()),
                eq(message.getVoucherId().toString()),
                eq(message.getOrderId().toString()),
                eq(SECKILL_ORDER_STATUS_TTL_SECONDS.toString()));
    }

    @Test
    void validationUsesExactStatusAndReservationKeys() {
        doReturn(1L).when(redisTemplate).execute(
                any(RedisScript.class), anyList(), any(), any(), any());

        assertThat(service.validateForConsumption(message))
                .isEqualTo(SeckillOrderStateService.ReservationDecision.PROCESS);

        verify(redisTemplate).execute(
                any(RedisScript.class),
                eq(Arrays.asList(
                        SECKILL_ORDER_STATUS_KEY + message.getOrderId(),
                        SECKILL_RESERVATION_KEY + message.getVoucherId())),
                eq(message.getUserId().toString()),
                eq(message.getVoucherId().toString()),
                eq(message.getOrderId().toString()));
    }

    @Test
    void validationMapsEveryTerminalAndUnsafeState() {
        doReturn(2L, 3L, 4L, 0L).when(redisTemplate).execute(
                any(RedisScript.class), anyList(), any(), any(), any());

        assertThat(service.validateForConsumption(message))
                .isEqualTo(SeckillOrderStateService.ReservationDecision.ALREADY_SUCCESS);
        assertThat(service.validateForConsumption(message))
                .isEqualTo(SeckillOrderStateService.ReservationDecision.ALREADY_FAILED);
        assertThat(service.validateForConsumption(message))
                .isEqualTo(SeckillOrderStateService.ReservationDecision.POISONED);
        assertThat(service.validateForConsumption(message))
                .isEqualTo(SeckillOrderStateService.ReservationDecision.RETRYABLE_STATE_MISSING);
    }

    @Test
    void compensationIsIdempotentWhenStatusIsAlreadyFailed() {
        doReturn(0L).when(redisTemplate).execute(
                any(RedisScript.class), anyList(), any(), any(), any(), any(), any());
        when(hashOperations.entries(SECKILL_ORDER_STATUS_KEY + message.getOrderId()))
                .thenReturn(state("FAILED", "DB_STOCK_EXHAUSTED"));

        assertThat(service.compensate(message, "DB_STOCK_EXHAUSTED")).isTrue();

        verify(redisTemplate).execute(
                any(RedisScript.class),
                eq(Arrays.asList(
                        SECKILL_STOCK_KEY + message.getVoucherId(),
                        SECKILL_ORDER_KEY + message.getVoucherId(),
                        SECKILL_RESERVATION_KEY + message.getVoucherId(),
                        SECKILL_ORDER_STATUS_KEY + message.getOrderId())),
                eq(message.getUserId().toString()),
                eq(message.getVoucherId().toString()),
                eq(message.getOrderId().toString()),
                eq("DB_STOCK_EXHAUSTED"),
                eq(SECKILL_ORDER_STATUS_TTL_SECONDS.toString()));
    }

    @Test
    void malformedOrCrossOwnedStateIsNeverAcceptedAsCompensated() {
        doReturn(0L).when(redisTemplate).execute(
                any(RedisScript.class), anyList(), any(), any(), any(), any(), any());
        Map<Object, Object> otherUsersState = state("FAILED", "DB_ORDER_CONFLICT");
        otherUsersState.put("userId", "24");
        when(hashOperations.entries(SECKILL_ORDER_STATUS_KEY + message.getOrderId()))
                .thenReturn(otherUsersState);

        assertThat(service.compensate(message, "DB_ORDER_CONFLICT")).isFalse();
    }

    private Map<Object, Object> state(String status, String reason) {
        Map<Object, Object> values = new HashMap<>();
        values.put("status", status);
        values.put("orderId", message.getOrderId().toString());
        values.put("userId", message.getUserId().toString());
        values.put("voucherId", message.getVoucherId().toString());
        values.put("reason", reason);
        return values;
    }
}
