package com.localdeals.trade.service;

import com.localdeals.trade.config.SeckillProperties;
import com.localdeals.trade.mq.SeckillOrderMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import static com.localdeals.platform.utils.RedisConstants.SECKILL_ORDER_STATUS_TTL_SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SeckillOrderStateServiceTest {

    private static final long USER_ID = 23L;
    /** Every generated order number repeats the buyer's gene; the keys depend on it. */
    private static final long ORDER_ID = (90071992547409931L & ~1023L) | USER_ID;
    private static final SeckillBucketRouter ROUTER = new SeckillBucketRouter(16);
    private static final int BUCKET = ROUTER.bucketOfUser(USER_ID);

    private StringRedisTemplate redisTemplate;
    private HashOperations<String, Object, Object> hashOperations;
    private SeckillOrderStateService service;
    private SeckillOrderMessage message;
    private SeckillSoldOutRegistry soldOut;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        redisTemplate = mock(StringRedisTemplate.class);
        hashOperations = mock(HashOperations.class);
        when(redisTemplate.opsForHash()).thenReturn(hashOperations);
        soldOut = mock(SeckillSoldOutRegistry.class);
        service = new SeckillOrderStateService(redisTemplate, new SeckillProperties(), soldOut, ROUTER);
        message = new SeckillOrderMessage(17L, USER_ID, ORDER_ID);
    }

    @Test
    void markSuccessUsesExactReservationAndStringOrderId() {
        doReturn(1L).when(redisTemplate).execute(
                any(RedisScript.class), anyList(), any(), any(), any(), any());

        assertThat(service.markSuccess(message)).isTrue();

        verify(redisTemplate).execute(
                any(RedisScript.class),
                eq(Arrays.asList(
                        ROUTER.statusKey(message.getOrderId(), BUCKET),
                        ROUTER.reservationKey(message.getVoucherId(), BUCKET),
                        ROUTER.processingKey(BUCKET))),
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
                        ROUTER.statusKey(message.getOrderId(), BUCKET),
                        ROUTER.reservationKey(message.getVoucherId(), BUCKET))),
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
        when(hashOperations.entries(ROUTER.statusKey(message.getOrderId(), BUCKET)))
                .thenReturn(state("FAILED", "DB_STOCK_EXHAUSTED"));

        assertThat(service.compensate(message, "DB_STOCK_EXHAUSTED")).isTrue();

        verify(redisTemplate).execute(
                any(RedisScript.class),
                eq(Arrays.asList(
                        ROUTER.stockKey(message.getVoucherId(), BUCKET),
                        ROUTER.reservationKey(message.getVoucherId(), BUCKET),
                        ROUTER.statusKey(message.getOrderId(), BUCKET),
                        ROUTER.processingKey(BUCKET))),
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
        when(hashOperations.entries(ROUTER.statusKey(message.getOrderId(), BUCKET)))
                .thenReturn(otherUsersState);

        assertThat(service.compensate(message, "DB_ORDER_CONFLICT")).isFalse();
    }

    @Test
    @SuppressWarnings("unchecked")
    void dueQueryAsksEveryBucketForItsShareAndDropsMalformedRawMembers() {
        org.springframework.data.redis.core.ZSetOperations<String, String> zSet =
                org.mockito.Mockito.mock(org.springframework.data.redis.core.ZSetOperations.class);
        when(redisTemplate.opsForZSet()).thenReturn(zSet);
        // The index is per bucket: a batch of 100 over sixteen buckets is seven each.
        doReturn(Arrays.asList("41", "not-a-long", "01", "+1", "", " ", "42"),
                java.util.List.of())
                .when(redisTemplate).execute(any(RedisScript.class), anyList(), eq("7"));

        assertThat(service.findDueOrderIds(500)).containsExactly(41L, 42L);

        for (int bucket = 0; bucket < ROUTER.count(); bucket++) {
            verify(redisTemplate).execute(
                    any(RedisScript.class),
                    eq(java.util.Collections.singletonList(ROUTER.processingKey(bucket))),
                    eq("7"));
        }
        for (String malformed : new String[]{"not-a-long", "01", "+1", "", " "}) {
            verify(zSet).remove(ROUTER.processingKey(0), malformed);
        }
    }

    @Test
    void claimReturnsServerTimesAndAttemptCount() {
        doReturn(Arrays.asList("1", "1770000100", "1770000000", "2"))
                .when(redisTemplate).execute(
                        any(RedisScript.class), anyList(), any(), any(), any(), any(), any());

        SeckillOrderStateService.ReconciliationClaim claim = service.claimForReconciliation(message);

        assertThat(claim.getDecision())
                .isEqualTo(SeckillOrderStateService.ReconciliationClaimDecision.CLAIMED);
        assertThat(claim.getRedisNow()).isEqualTo(1770000100L);
        assertThat(claim.getCreatedAt()).isEqualTo(1770000000L);
        assertThat(claim.getReconcileAttempts()).isEqualTo(2L);
        verify(redisTemplate).execute(
                any(RedisScript.class),
                eq(Arrays.asList(
                        ROUTER.statusKey(message.getOrderId(), BUCKET),
                        ROUTER.reservationKey(message.getVoucherId(), BUCKET),
                        ROUTER.processingKey(BUCKET))),
                eq(message.getUserId().toString()),
                eq(message.getVoucherId().toString()),
                eq(message.getOrderId().toString()),
                eq("60"),
                // The reconciler claims under the same owner field the consumer batch uses.
                eq(service.claimOwner()));
    }

    @Test
    void unresolvedStateDeferralUsesOnlyTheSchedulerKeys() {
        doReturn(1L).when(redisTemplate).execute(
                any(RedisScript.class), anyList(), any(), any());

        assertThat(service.deferProcessingOrder(message.getOrderId())).isTrue();

        verify(redisTemplate).execute(
                any(RedisScript.class),
                eq(Arrays.asList(
                        ROUTER.statusKey(message.getOrderId(), BUCKET),
                        ROUTER.processingKey(BUCKET))),
                eq(message.getOrderId().toString()),
                eq("60"));
    }

    @Test
    void exactAlreadyFailedScriptCodeIsAcceptedWithoutFallbackRead() {
        doReturn(2L).when(redisTemplate).execute(
                any(RedisScript.class), anyList(), any(), any(), any(), any(), any());

        assertThat(service.compensate(message, "PROCESSING_TIMEOUT")).isTrue();

        org.mockito.Mockito.verifyNoInteractions(hashOperations);
    }

    @Test
    void aFreshCompensationGivesTheUnitBackAndClearsTheSoldOutFlag() {
        doReturn(1L).when(redisTemplate).execute(
                any(RedisScript.class), anyList(), any(), any(), any(), any(), any());

        assertThat(service.compensate(message, "DB_STOCK_EXHAUSTED")).isTrue();

        verify(soldOut).clear(17L, BUCKET);
    }

    @Test
    void aReplayedCompensationDoesNotBroadcast() {
        doReturn(2L).when(redisTemplate).execute(
                any(RedisScript.class), anyList(), any(), any(), any(), any(), any());

        service.compensate(message, "DB_STOCK_EXHAUSTED");

        org.mockito.Mockito.verifyNoInteractions(soldOut);
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
