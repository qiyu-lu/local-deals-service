package com.localdeals.trade.service;

import com.localdeals.trade.config.SeckillProperties;
import com.localdeals.trade.mq.SeckillOrderMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.Arrays;
import java.util.List;

import static com.localdeals.platform.utils.RedisConstants.SECKILL_ORDER_STATUS_KEY;
import static com.localdeals.platform.utils.RedisConstants.SECKILL_PROCESSING_INDEX_KEY;
import static com.localdeals.platform.utils.RedisConstants.SECKILL_RESERVATION_KEY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * M4 replaces the per-message Redisson lock with an in-Redis claim: one round trip classifies a
 * whole batch and leases it away from the reconciler.
 */
class SeckillOrderStateBatchTest {

    private StringRedisTemplate redisTemplate;
    private SeckillOrderStateService service;
    private SeckillOrderMessage first;
    private SeckillOrderMessage second;

    @BeforeEach
    void setUp() {
        redisTemplate = mock(StringRedisTemplate.class);
        service = new SeckillOrderStateService(
                redisTemplate, new SeckillProperties(), mock(SeckillSoldOutRegistry.class));
        first = new SeckillOrderMessage(7L, 101L, 9001L);
        second = new SeckillOrderMessage(8L, 102L, 9002L);
    }

    @Test
    void oneRoundTripClaimsTheWholeBatchAndKeepsPerMessageDecisions() {
        doReturn(Arrays.asList(1L, 5L)).when(redisTemplate)
                .execute(any(RedisScript.class), anyList(), any(Object[].class));

        List<SeckillOrderStateService.PersistClaim> claims =
                service.claimForPersistence(Arrays.asList(first, second));

        assertThat(claims).containsExactly(
                SeckillOrderStateService.PersistClaim.CLAIMED,
                SeckillOrderStateService.PersistClaim.CLAIM_BUSY);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> keys = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(redisTemplate).execute(any(RedisScript.class), keys.capture(), args.capture());
        assertThat(keys.getValue()).containsExactly(
                SECKILL_PROCESSING_INDEX_KEY,
                SECKILL_ORDER_STATUS_KEY + 9001L, SECKILL_RESERVATION_KEY + 7L,
                SECKILL_ORDER_STATUS_KEY + 9002L, SECKILL_RESERVATION_KEY + 8L);
        // owner, lease seconds, then one (userId, voucherId, orderId) triple per message
        assertThat(args.getValue()).hasSize(2 + 2 * 3);
        assertThat(args.getValue()[0]).isEqualTo(service.claimOwner());
        assertThat(Arrays.copyOfRange(args.getValue(), 2, 8)).containsExactly(
                "101", "7", "9001", "102", "8", "9002");
    }

    @Test
    void everyBatchClaimCodeMapsToADecision() {
        doReturn(Arrays.asList(1L, 2L, 3L, 4L, 0L, 5L)).when(redisTemplate)
                .execute(any(RedisScript.class), anyList(), any(Object[].class));

        List<SeckillOrderMessage> batch = Arrays.asList(first, first, first, first, first, first);

        assertThat(service.claimForPersistence(batch)).containsExactly(
                SeckillOrderStateService.PersistClaim.CLAIMED,
                SeckillOrderStateService.PersistClaim.ALREADY_SUCCESS,
                SeckillOrderStateService.PersistClaim.ALREADY_FAILED,
                SeckillOrderStateService.PersistClaim.POISONED,
                SeckillOrderStateService.PersistClaim.STATE_MISSING,
                SeckillOrderStateService.PersistClaim.CLAIM_BUSY);
    }

    @Test
    void markSuccessBatchReportsEachOrderSeparately() {
        doReturn(Arrays.asList(1L, 0L)).when(redisTemplate)
                .execute(any(RedisScript.class), anyList(), any(Object[].class));

        assertThat(service.markSuccessBatch(Arrays.asList(first, second)))
                .containsExactly(true, false);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> keys = ArgumentCaptor.forClass(List.class);
        verify(redisTemplate).execute(any(RedisScript.class), keys.capture(), any(Object[].class));
        assertThat(keys.getValue()).containsExactly(
                SECKILL_PROCESSING_INDEX_KEY,
                SECKILL_ORDER_STATUS_KEY + 9001L, SECKILL_RESERVATION_KEY + 7L,
                SECKILL_ORDER_STATUS_KEY + 9002L, SECKILL_RESERVATION_KEY + 8L);
    }

    @Test
    void anEmptyBatchNeverTalksToRedis() {
        assertThat(service.claimForPersistence(List.of())).isEmpty();
        assertThat(service.markSuccessBatch(List.of())).isEmpty();

        verify(redisTemplate, org.mockito.Mockito.never())
                .execute(any(RedisScript.class), anyList(), any(Object[].class));
    }

    @Test
    void aShortReplyIsAnErrorRatherThanASilentlyTruncatedBatch() {
        doReturn(List.of(1L)).when(redisTemplate)
                .execute(any(RedisScript.class), anyList(), any(Object[].class));

        assertThat(org.assertj.core.api.Assertions
                .catchThrowable(() -> service.claimForPersistence(Arrays.asList(first, second))))
                .isInstanceOf(IllegalStateException.class);
    }
}
