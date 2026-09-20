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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * M4 replaces the per-message Redisson lock with an in-Redis claim: one round trip classifies a
 * whole batch and leases it away from the reconciler. M5 splits that round trip per stock
 * bucket, because a Cluster call may only touch keys of one slot — the answers still come back
 * in the caller's order.
 */
class SeckillOrderStateBatchTest {

    private static final SeckillBucketRouter ROUTER = new SeckillBucketRouter(16);
    /** Both buyers sit in the same bucket, so a plain batch stays one round trip. */
    private static final long USER_A = 101L;
    private static final long USER_B = 117L;
    /** A third buyer, one bucket over. */
    private static final long USER_C = 102L;
    private static final int BUCKET = ROUTER.bucketOfUser(USER_A);
    private static final int OTHER_BUCKET = ROUTER.bucketOfUser(USER_C);
    private static final long ORDER_A = 9000L - (9000L & 1023L) + USER_A % 1024;
    private static final long ORDER_B = 10240L + USER_B % 1024;
    private static final long ORDER_C = 11264L + USER_C % 1024;

    private StringRedisTemplate redisTemplate;
    private SeckillOrderStateService service;
    private SeckillOrderMessage first;
    private SeckillOrderMessage second;
    private SeckillOrderMessage other;

    @BeforeEach
    void setUp() {
        redisTemplate = mock(StringRedisTemplate.class);
        service = new SeckillOrderStateService(
                redisTemplate, new SeckillProperties(), mock(SeckillSoldOutRegistry.class), ROUTER);
        first = new SeckillOrderMessage(7L, USER_A, ORDER_A);
        second = new SeckillOrderMessage(8L, USER_B, ORDER_B);
        other = new SeckillOrderMessage(8L, USER_C, ORDER_C);
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
                ROUTER.processingKey(BUCKET),
                ROUTER.statusKey(ORDER_A, BUCKET), ROUTER.reservationKey(7L, BUCKET),
                ROUTER.statusKey(ORDER_B, BUCKET), ROUTER.reservationKey(8L, BUCKET));
        // owner, lease seconds, then one (userId, voucherId, orderId) triple per message
        assertThat(args.getValue()).hasSize(2 + 2 * 3);
        assertThat(args.getValue()[0]).isEqualTo(service.claimOwner());
        assertThat(Arrays.copyOfRange(args.getValue(), 2, 8)).containsExactly(
                Long.toString(USER_A), "7", Long.toString(ORDER_A),
                Long.toString(USER_B), "8", Long.toString(ORDER_B));
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
                ROUTER.processingKey(BUCKET),
                ROUTER.statusKey(ORDER_A, BUCKET), ROUTER.reservationKey(7L, BUCKET),
                ROUTER.statusKey(ORDER_B, BUCKET), ROUTER.reservationKey(8L, BUCKET));
    }

    @Test
    @SuppressWarnings("unchecked")
    void aBatchAcrossBucketsIsOneCallPerBucketAndKeepsTheCallerOrder() {
        // Buyer order: other (bucket B), first (bucket A), second (bucket A).
        doReturn(List.of(5L), Arrays.asList(1L, 2L)).when(redisTemplate)
                .execute(any(RedisScript.class), anyList(), any(Object[].class));

        assertThat(service.claimForPersistence(Arrays.asList(other, first, second)))
                .containsExactly(
                        SeckillOrderStateService.PersistClaim.CLAIM_BUSY,
                        SeckillOrderStateService.PersistClaim.CLAIMED,
                        SeckillOrderStateService.PersistClaim.ALREADY_SUCCESS);

        ArgumentCaptor<List<String>> keys = ArgumentCaptor.forClass(List.class);
        verify(redisTemplate, org.mockito.Mockito.times(2))
                .execute(any(RedisScript.class), keys.capture(), any(Object[].class));
        assertThat(keys.getAllValues().get(0)).containsExactly(
                ROUTER.processingKey(OTHER_BUCKET),
                ROUTER.statusKey(ORDER_C, OTHER_BUCKET), ROUTER.reservationKey(8L, OTHER_BUCKET));
        assertThat(keys.getAllValues().get(1)).containsExactly(
                ROUTER.processingKey(BUCKET),
                ROUTER.statusKey(ORDER_A, BUCKET), ROUTER.reservationKey(7L, BUCKET),
                ROUTER.statusKey(ORDER_B, BUCKET), ROUTER.reservationKey(8L, BUCKET));
        // Each call stays inside one slot.
        for (List<String> call : keys.getAllValues()) {
            assertThat(call.stream().map(SeckillOrderStateBatchTest::hashTag).distinct()).hasSize(1);
        }
    }

    private static String hashTag(String key) {
        return key.substring(key.indexOf('{') + 1, key.indexOf('}'));
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
