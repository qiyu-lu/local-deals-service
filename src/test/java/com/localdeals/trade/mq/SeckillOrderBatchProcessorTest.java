package com.localdeals.trade.mq;

import com.localdeals.platform.observability.LocalDealsMetrics;
import com.localdeals.platform.websocket.WebSocketNotifier;
import com.localdeals.trade.exception.BatchPersistDegradedException;
import com.localdeals.trade.service.SeckillOrderBatchPersister;
import com.localdeals.trade.service.SeckillOrderStateService;
import com.localdeals.trade.service.SeckillOrderStateService.PersistClaim;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class SeckillOrderBatchProcessorTest {

    private SeckillOrderStateService stateService;
    private SeckillOrderBatchPersister batchPersister;
    private SeckillOrderConsumer singleMessageConsumer;
    private OrderTimeoutScheduler orderTimeoutScheduler;
    private WebSocketNotifier webSocketNotifier;
    private SimpleMeterRegistry registry;
    private SeckillOrderBatchProcessor processor;

    private final SeckillOrderMessage first = new SeckillOrderMessage(7L, 101L, 9001L);
    private final SeckillOrderMessage second = new SeckillOrderMessage(7L, 102L, 9002L);
    private final SeckillOrderMessage otherVoucher = new SeckillOrderMessage(8L, 103L, 9003L);

    @BeforeEach
    void setUp() {
        stateService = mock(SeckillOrderStateService.class);
        batchPersister = mock(SeckillOrderBatchPersister.class);
        singleMessageConsumer = mock(SeckillOrderConsumer.class);
        orderTimeoutScheduler = mock(OrderTimeoutScheduler.class);
        webSocketNotifier = mock(WebSocketNotifier.class);
        registry = new SimpleMeterRegistry();
        processor = new SeckillOrderBatchProcessor(stateService, batchPersister,
                singleMessageConsumer, orderTimeoutScheduler, webSocketNotifier,
                new LocalDealsMetrics(registry));
    }

    private DistributionSummary batchSize(String stage) {
        return registry.find("local_deals.seckill.consume.batch.size").tag("stage", stage).summary();
    }

    private double degraded(String reason) {
        Counter counter = registry.find("local_deals.seckill.consume.degraded")
                .tag("reason", reason).counter();
        return counter == null ? -1 : counter.count();
    }

    // M5 could not read its own persistence A/B because nothing recorded how full the batches
    // actually were: "the slow rounds had one or two orders per batch" stayed an inference from
    // M4 onwards. These two metrics are what turns it into a measurement.
    @Test
    void theDeliveredBatchAndEachPersistedGroupAreMeasured() {
        List<SeckillOrderMessage> batch = Arrays.asList(first, otherVoucher, second);
        when(stateService.claimForPersistence(batch)).thenReturn(
                Arrays.asList(PersistClaim.CLAIMED, PersistClaim.CLAIMED, PersistClaim.CLAIMED));
        when(stateService.markSuccessBatch(anyList()))
                .thenReturn(Arrays.asList(true, true, true));

        assertThat(processor.process(batch)).isTrue();

        // What RocketMQ handed over: one batch of three.
        assertThat(batchSize("delivered").count()).isEqualTo(1);
        assertThat(batchSize("delivered").totalAmount()).isEqualTo(3);
        // What one INSERT + one stock update actually carried: two groups, of two and of one.
        assertThat(batchSize("persisted").count()).isEqualTo(2);
        assertThat(batchSize("persisted").totalAmount()).isEqualTo(3);
        assertThat(batchSize("persisted").max()).isEqualTo(2);
    }

    @Test
    void aBatchIsMeasuredAsDeliveredEvenWhenNothingIsClaimed() {
        List<SeckillOrderMessage> batch = Arrays.asList(first, second);
        when(stateService.claimForPersistence(batch)).thenReturn(
                Arrays.asList(PersistClaim.ALREADY_SUCCESS, PersistClaim.ALREADY_FAILED));

        assertThat(processor.process(batch)).isTrue();

        assertThat(batchSize("delivered").count()).isEqualTo(1);
        assertThat(batchSize("delivered").totalAmount()).isEqualTo(2);
        assertThat(batchSize("persisted").count()).isZero();
    }

    @Test
    void eachDegradationIsCountedByWhatMadeTheFastPathStopHolding() {
        List<SeckillOrderMessage> batch = Arrays.asList(first, second);
        when(stateService.claimForPersistence(batch)).thenReturn(
                Arrays.asList(PersistClaim.CLAIMED, PersistClaim.CLAIMED));
        doThrow(BatchPersistDegradedException.insertSkipped("voucherId=7 covered 1 of 2"))
                .when(batchPersister).persistGroup(eq(7L), anyList());

        assertThat(processor.process(batch)).isTrue();

        assertThat(degraded("insert_skipped")).isEqualTo(1);
        assertThat(degraded("stock_short")).isZero();
        // A degraded group never reached one INSERT, so it is not a persisted group.
        assertThat(batchSize("persisted").count()).isZero();
    }

    @Test
    void aStockGuardThatNoLongerHoldsIsCountedApartFromASkippedInsert() {
        List<SeckillOrderMessage> batch = List.of(first);
        when(stateService.claimForPersistence(batch)).thenReturn(List.of(PersistClaim.CLAIMED));
        doThrow(BatchPersistDegradedException.stockShort("voucherId=7 orders=1"))
                .when(batchPersister).persistGroup(eq(7L), anyList());

        assertThat(processor.process(batch)).isTrue();

        assertThat(degraded("stock_short")).isEqualTo(1);
        assertThat(degraded("insert_skipped")).isZero();
    }

    @Test
    void eachVoucherOfABatchIsPersistedOnce() {
        List<SeckillOrderMessage> batch = Arrays.asList(first, otherVoucher, second);
        when(stateService.claimForPersistence(batch)).thenReturn(
                Arrays.asList(PersistClaim.CLAIMED, PersistClaim.CLAIMED, PersistClaim.CLAIMED));
        when(stateService.markSuccessBatch(anyList()))
                .thenReturn(Arrays.asList(true, true, true));

        assertThat(processor.process(batch)).isTrue();

        verify(batchPersister).persistGroup(eq(7L), eq(Arrays.asList(first, second)));
        verify(batchPersister).persistGroup(eq(8L), eq(List.of(otherVoucher)));
        verifyNoInteractions(singleMessageConsumer);
        // One announcement for the whole batch, and no broker round trip on the consume thread.
        // grouped by voucher, so the announcement follows the persistence order
        verify(webSocketNotifier).notifySeckillBatch(Arrays.asList(first, second, otherVoucher));
        verify(orderTimeoutScheduler).scheduleCloseAsync(9001L);
        verify(orderTimeoutScheduler, never()).scheduleClose(anyLong());
    }

    @Test
    void alreadyFinishedMessagesAreAcknowledgedWithoutTouchingMySql() {
        List<SeckillOrderMessage> batch = Arrays.asList(first, second);
        when(stateService.claimForPersistence(batch)).thenReturn(
                Arrays.asList(PersistClaim.ALREADY_SUCCESS, PersistClaim.ALREADY_FAILED));

        assertThat(processor.process(batch)).isTrue();

        verifyNoInteractions(batchPersister);
        verifyNoInteractions(singleMessageConsumer);
        verify(stateService, never()).markSuccessBatch(anyList());
    }

    @Test
    void aClaimHeldElsewhereRedeliversTheBatch() {
        List<SeckillOrderMessage> batch = List.of(first);
        when(stateService.claimForPersistence(batch)).thenReturn(List.of(PersistClaim.CLAIM_BUSY));

        assertThat(processor.process(batch)).isFalse();

        verifyNoInteractions(batchPersister);
    }

    @Test
    void aDegradedGroupFallsBackToTheSingleMessagePath() {
        List<SeckillOrderMessage> batch = Arrays.asList(first, second);
        when(stateService.claimForPersistence(batch)).thenReturn(
                Arrays.asList(PersistClaim.CLAIMED, PersistClaim.CLAIMED));
        doThrow(BatchPersistDegradedException.insertSkipped("insert covered 1 of 2"))
                .when(batchPersister).persistGroup(eq(7L), anyList());

        assertThat(processor.process(batch)).isTrue();

        verify(singleMessageConsumer).onMessage(first);
        verify(singleMessageConsumer).onMessage(second);
        // The single-message path finalizes Redis itself; the batch must not do it again.
        verify(stateService, never()).markSuccessBatch(anyList());
    }

    @Test
    void aSingleMessageFailureInsideADegradedGroupRedeliversTheBatch() {
        List<SeckillOrderMessage> batch = List.of(first);
        when(stateService.claimForPersistence(batch)).thenReturn(List.of(PersistClaim.CLAIMED));
        doThrow(BatchPersistDegradedException.insertSkipped("insert covered 0 of 1"))
                .when(batchPersister).persistGroup(anyLong(), anyList());
        doThrow(new IllegalStateException("db down")).when(singleMessageConsumer).onMessage(first);

        assertThat(processor.process(batch)).isFalse();
    }

    @Test
    void aCommittedBatchWhoseRedisFinalizationFailsIsRedelivered() {
        List<SeckillOrderMessage> batch = List.of(first);
        when(stateService.claimForPersistence(batch)).thenReturn(List.of(PersistClaim.CLAIMED));
        when(stateService.markSuccessBatch(anyList()))
                .thenThrow(new IllegalStateException("redis down"));

        assertThat(processor.process(batch)).isFalse();

        // Nothing is announced for an order whose reservation is not finalized yet.
        verifyNoInteractions(webSocketNotifier);
        verifyNoInteractions(orderTimeoutScheduler);
    }

    @Test
    void anUnreadableMessageIsDroppedInsteadOfPoisoningItsBatch() {
        List<SeckillOrderMessage> batch = Arrays.asList(null, first);
        when(stateService.claimForPersistence(List.of(first)))
                .thenReturn(List.of(PersistClaim.CLAIMED));
        when(stateService.markSuccessBatch(anyList())).thenReturn(List.of(true));

        assertThat(processor.process(batch)).isTrue();

        verify(batchPersister).persistGroup(eq(7L), eq(List.of(first)));
    }

    @Test
    void anUnavailableRedisRedeliversTheBatchWithoutTouchingMySql() {
        List<SeckillOrderMessage> batch = List.of(first);
        when(stateService.claimForPersistence(any())).thenThrow(new IllegalStateException("redis down"));

        assertThat(processor.process(batch)).isFalse();

        verifyNoInteractions(batchPersister);
    }
}
