package com.localdeals.service;

import com.localdeals.config.SeckillProperties;
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
import static com.localdeals.utils.RedisConstants.SECKILL_PROCESSING_INDEX_KEY;
import static com.localdeals.utils.RedisConstants.SECKILL_PROCESSING_QUARANTINE_KEY;
import static com.localdeals.utils.RedisConstants.SECKILL_PROCESSING_QUARANTINE_REASON_KEY;
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
        service = new SeckillOrderStateService(redisTemplate, new SeckillProperties());
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
                        SECKILL_RESERVATION_KEY + message.getVoucherId(),
                        SECKILL_PROCESSING_INDEX_KEY)),
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
                        SECKILL_RESERVATION_KEY + message.getVoucherId(),
                        SECKILL_PROCESSING_QUARANTINE_KEY)),
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
                        SECKILL_ORDER_STATUS_KEY + message.getOrderId(),
                        SECKILL_PROCESSING_INDEX_KEY,
                        SECKILL_PROCESSING_QUARANTINE_KEY)),
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

    @Test
    void dueQueryIsBoundedAndQuarantinesMalformedRawMemberWithoutDroppingValidIds() {
        doReturn(Arrays.asList("41", "not-a-long", "01", "+1", "", " ", "42"))
                .when(redisTemplate).execute(
                any(RedisScript.class), anyList(), eq("100"));
        doReturn(1L).when(redisTemplate).execute(
                any(RedisScript.class), anyList(), any(),
                eq("INVALID_PROCESSING_INDEX_MEMBER"));

        assertThat(service.findDueOrderIds(500)).containsExactly(41L, 42L);

        verify(redisTemplate).execute(
                any(RedisScript.class),
                eq(java.util.Collections.singletonList(SECKILL_PROCESSING_INDEX_KEY)),
                eq("100"));
        verify(redisTemplate).execute(
                any(RedisScript.class),
                eq(Arrays.asList(
                        SECKILL_PROCESSING_INDEX_KEY,
                        SECKILL_PROCESSING_QUARANTINE_KEY,
                        SECKILL_PROCESSING_QUARANTINE_REASON_KEY)),
                eq("not-a-long"), eq("INVALID_PROCESSING_INDEX_MEMBER"));
        verify(redisTemplate).execute(any(RedisScript.class), anyList(),
                eq("01"), eq("INVALID_PROCESSING_INDEX_MEMBER"));
        verify(redisTemplate).execute(any(RedisScript.class), anyList(),
                eq("+1"), eq("INVALID_PROCESSING_INDEX_MEMBER"));
        verify(redisTemplate).execute(any(RedisScript.class), anyList(),
                eq(""), eq("INVALID_PROCESSING_INDEX_MEMBER"));
        verify(redisTemplate).execute(any(RedisScript.class), anyList(),
                eq(" "), eq("INVALID_PROCESSING_INDEX_MEMBER"));
    }

    @Test
    void claimReturnsServerTimesAndAttemptCount() {
        doReturn(Arrays.asList("1", "1770000100", "1770000000", "2"))
                .when(redisTemplate).execute(
                        any(RedisScript.class), anyList(), any(), any(), any(), any());

        SeckillOrderStateService.ReconciliationClaim claim = service.claimForReconciliation(message);

        assertThat(claim.getDecision())
                .isEqualTo(SeckillOrderStateService.ReconciliationClaimDecision.CLAIMED);
        assertThat(claim.getRedisNow()).isEqualTo(1770000100L);
        assertThat(claim.getCreatedAt()).isEqualTo(1770000000L);
        assertThat(claim.getReconcileAttempts()).isEqualTo(2L);
        verify(redisTemplate).execute(
                any(RedisScript.class),
                eq(Arrays.asList(
                        SECKILL_ORDER_STATUS_KEY + message.getOrderId(),
                        SECKILL_RESERVATION_KEY + message.getVoucherId(),
                        SECKILL_PROCESSING_INDEX_KEY,
                        SECKILL_PROCESSING_QUARANTINE_KEY)),
                eq(message.getUserId().toString()),
                eq(message.getVoucherId().toString()),
                eq(message.getOrderId().toString()),
                eq("60"));
    }

    @Test
    void unresolvedStateDeferralUsesOnlySchedulerAndSafetyKeys() {
        doReturn(1L).when(redisTemplate).execute(
                any(RedisScript.class), anyList(), any(), any());

        assertThat(service.deferProcessingOrder(message.getOrderId())).isTrue();

        verify(redisTemplate).execute(
                any(RedisScript.class),
                eq(Arrays.asList(
                        SECKILL_ORDER_STATUS_KEY + message.getOrderId(),
                        SECKILL_PROCESSING_INDEX_KEY,
                        SECKILL_PROCESSING_QUARANTINE_KEY)),
                eq(message.getOrderId().toString()),
                eq("60"));
    }

    @Test
    void backfillMapsExactAndUnsafeDecisions() {
        doReturn(1L, 2L, 3L, 4L, 5L, 6L, 7L).when(redisTemplate).execute(
                any(RedisScript.class), anyList(), any(), any(), any(), any());

        assertThat(service.backfillProcessingOrder(message))
                .isEqualTo(SeckillOrderStateService.ProcessingBackfillDecision.INDEXED);
        assertThat(service.backfillProcessingOrder(message))
                .isEqualTo(SeckillOrderStateService.ProcessingBackfillDecision.ALREADY_INDEXED);
        assertThat(service.backfillProcessingOrder(message))
                .isEqualTo(SeckillOrderStateService.ProcessingBackfillDecision.TERMINAL);
        assertThat(service.backfillProcessingOrder(message))
                .isEqualTo(SeckillOrderStateService.ProcessingBackfillDecision.OWNERSHIP_MISMATCH);
        assertThat(service.backfillProcessingOrder(message))
                .isEqualTo(SeckillOrderStateService.ProcessingBackfillDecision.STATE_INVALID);
        assertThat(service.backfillProcessingOrder(message))
                .isEqualTo(SeckillOrderStateService.ProcessingBackfillDecision.RESERVATION_MISMATCH);
        assertThat(service.backfillProcessingOrder(message))
                .isEqualTo(SeckillOrderStateService.ProcessingBackfillDecision.QUARANTINED);
    }

    @Test
    void exactAlreadyFailedScriptCodeIsAcceptedWithoutFallbackRead() {
        doReturn(2L).when(redisTemplate).execute(
                any(RedisScript.class), anyList(), any(), any(), any(), any(), any());

        assertThat(service.compensate(message, "PROCESSING_TIMEOUT")).isTrue();

        org.mockito.Mockito.verifyNoInteractions(hashOperations);
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
