package com.localdeals.trade.service;

import com.localdeals.trade.config.SeckillProperties;
import com.localdeals.trade.dto.SeckillOrderPersistenceResult;
import com.localdeals.trade.mq.SeckillOrderMessage;
import com.localdeals.trade.mq.SeckillOrderProducer;
import com.localdeals.platform.websocket.WebSocketNotifier;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;

import java.time.Duration;
import java.util.Collections;

import static com.localdeals.trade.service.SeckillOrderStateService.ReconciliationClaimDecision.CLAIMED;
import static com.localdeals.trade.service.SeckillOrderStateService.ReconciliationClaimDecision.RESERVATION_MISMATCH;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SeckillOrderReconcilerTest {

    private static final Long ORDER_ID = 90071992547409931L;
    private static final Long USER_ID = 23L;
    private static final Long VOUCHER_ID = 17L;
    private static final long REDIS_NOW = 10_000L;

    @Mock
    private SeckillOrderStateService stateService;
    @Mock
    private IVoucherOrderService voucherOrderService;
    @Mock
    private RedissonClient redissonClient;
    @Mock
    private RLock schedulingLock;
    @Mock
    private WebSocketNotifier webSocketNotifier;
    @Mock
    private SeckillOrderProducer producer;

    private SeckillProperties properties;
    private SeckillOrderReconciler reconciler;

    @BeforeEach
    void setUp() {
        properties = new SeckillProperties();
        properties.getReconciliation().setEnabled(true);
        properties.getReconciliation().setCompensationEnabled(false);
        properties.getReconciliation().setFinalTimeout(Duration.ofMinutes(15));

        reconciler = new SeckillOrderReconciler(
                stateService, voucherOrderService, redissonClient, webSocketNotifier,
                producer, properties, new SimpleMeterRegistry());

        lenient().when(stateService.findDueOrderIds(100))
                .thenReturn(Collections.singletonList(ORDER_ID));
        lenient().when(stateService.find(ORDER_ID)).thenReturn(
                new SeckillOrderStateService.Snapshot(
                        ORDER_ID, USER_ID, VOUCHER_ID,
                        SeckillOrderStateService.STATUS_PROCESSING, null));
        lenient().when(redissonClient.getLock("lock:seckill:reconcile:" + ORDER_ID))
                .thenReturn(schedulingLock);
        lenient().when(schedulingLock.tryLock()).thenReturn(true);
        lenient().when(schedulingLock.isHeldByCurrentThread()).thenReturn(true);
        lenient().when(stateService.claimForReconciliation(any())).thenReturn(
                claim(REDIS_NOW - 60, 1));
    }

    @Test
    void disabledWorkerDoesNotReadRedis() {
        properties.getReconciliation().setEnabled(false);

        reconciler.reconcileDueOrders();

        verify(stateService, never()).findDueOrderIds(anyInt());
    }

    @Test
    void exactPersistedOrderRepairsSuccessAndNotifies() {
        when(voucherOrderService.classifyPersistence(ORDER_ID, USER_ID, VOUCHER_ID))
                .thenReturn(SeckillOrderPersistenceResult.exact(ORDER_ID, USER_ID, VOUCHER_ID));
        when(stateService.markSuccess(message())).thenReturn(true);

        reconciler.reconcileDueOrders();

        InOrder order = inOrder(schedulingLock, stateService, voucherOrderService);
        order.verify(schedulingLock).tryLock();
        order.verify(stateService).claimForReconciliation(message());
        order.verify(voucherOrderService).classifyPersistence(ORDER_ID, USER_ID, VOUCHER_ID);
        order.verify(stateService).markSuccess(message());
        order.verify(schedulingLock).isHeldByCurrentThread();
        order.verify(schedulingLock).unlock();
        // M4: no per-user Redisson lock anywhere on this path.
        verify(redissonClient, never()).getLock("lock:order:" + USER_ID);
        verify(stateService, never()).compensate(any(), anyString());
        verify(webSocketNotifier).notify(USER_ID, true, ORDER_ID, VOUCHER_ID);
    }

    /**
     * The reservation is the outbox: an admitted order whose message never reached the broker
     * (send failed, or the process died right after the Lua) is published again.
     */
    @Test
    void absentOrderBeforeHardDeadlineIsRedrivenWithTheExactMessage() {
        when(voucherOrderService.classifyPersistence(ORDER_ID, USER_ID, VOUCHER_ID))
                .thenReturn(SeckillOrderPersistenceResult.absent());
        when(stateService.claimForReconciliation(any())).thenReturn(
                claim(REDIS_NOW - Duration.ofMinutes(14).getSeconds(), 4));

        reconciler.reconcileDueOrders();

        verify(producer).publish(message());
        verify(stateService, never()).compensate(any(), anyString());
        verifyNoInteractions(webSocketNotifier);
    }

    @Test
    void aFailedRedriveLeavesTheReservationForTheNextRound() {
        when(voucherOrderService.classifyPersistence(ORDER_ID, USER_ID, VOUCHER_ID))
                .thenReturn(SeckillOrderPersistenceResult.absent());
        doThrow(new IllegalStateException("broker unavailable")).when(producer).publish(any());

        reconciler.reconcileDueOrders();

        verify(producer).publish(message());
        verify(stateService, never()).compensate(any(), anyString());
    }

    @Test
    void absentOrderAtHardDeadlineRemainsProcessingWhenCompensationDisabled() {
        when(voucherOrderService.classifyPersistence(ORDER_ID, USER_ID, VOUCHER_ID))
                .thenReturn(SeckillOrderPersistenceResult.absent());
        when(stateService.claimForReconciliation(any())).thenReturn(
                claim(REDIS_NOW - Duration.ofMinutes(15).getSeconds(), 8));

        reconciler.reconcileDueOrders();

        verify(producer, never()).publish(any());
        verify(stateService, never()).compensate(any(), anyString());
    }

    @Test
    void absentOrderAtHardDeadlineIsExactlyCompensatedWhenEnabled() {
        properties.getReconciliation().setCompensationEnabled(true);
        when(voucherOrderService.classifyPersistence(ORDER_ID, USER_ID, VOUCHER_ID))
                .thenReturn(SeckillOrderPersistenceResult.absent());
        when(stateService.claimForReconciliation(any())).thenReturn(
                claim(REDIS_NOW - Duration.ofMinutes(16).getSeconds(), 8));
        when(stateService.compensate(message(), "PROCESSING_TIMEOUT")).thenReturn(true);

        reconciler.reconcileDueOrders();

        verify(stateService).compensate(message(), "PROCESSING_TIMEOUT");
        verify(webSocketNotifier).notify(USER_ID, false, ORDER_ID, VOUCHER_ID);
    }

    @Test
    void databaseFailureNeverBecomesAbsenceOrCompensation() {
        when(voucherOrderService.classifyPersistence(ORDER_ID, USER_ID, VOUCHER_ID))
                .thenThrow(new IllegalStateException("writer unavailable"));

        reconciler.reconcileDueOrders();

        verify(stateService, never()).markSuccess(any());
        verify(stateService, never()).compensate(any(), anyString());
    }

    @Test
    void pairConflictSuspendsButDoesNotCompensateWhenFlagIsOff() {
        when(voucherOrderService.classifyPersistence(ORDER_ID, USER_ID, VOUCHER_ID))
                .thenReturn(SeckillOrderPersistenceResult.userVoucherConflict(777L, USER_ID, VOUCHER_ID));

        reconciler.reconcileDueOrders();

        verify(stateService).suspendVoucher(VOUCHER_ID, "DB_ORDER_CONFLICT");
        verify(stateService, never()).compensate(any(), anyString());
    }

    @Test
    void pairConflictSuspendsBeforeExactCompensationWhenFlagIsOn() {
        properties.getReconciliation().setCompensationEnabled(true);
        when(voucherOrderService.classifyPersistence(ORDER_ID, USER_ID, VOUCHER_ID))
                .thenReturn(SeckillOrderPersistenceResult.userVoucherConflict(777L, USER_ID, VOUCHER_ID));
        when(stateService.compensate(message(), "DB_ORDER_CONFLICT")).thenReturn(true);

        reconciler.reconcileDueOrders();

        InOrder order = inOrder(stateService);
        order.verify(stateService).suspendVoucher(VOUCHER_ID, "DB_ORDER_CONFLICT");
        order.verify(stateService).compensate(message(), "DB_ORDER_CONFLICT");
        verify(webSocketNotifier).notify(USER_ID, false, ORDER_ID, VOUCHER_ID);
    }

    @Test
    void orderIdConflictIsCompensatedLikeAPairConflict() {
        properties.getReconciliation().setCompensationEnabled(true);
        when(voucherOrderService.classifyPersistence(ORDER_ID, USER_ID, VOUCHER_ID))
                .thenReturn(SeckillOrderPersistenceResult.orderIdConflict(ORDER_ID, 99L, 88L));
        when(stateService.compensate(message(), "DB_ORDER_ID_CONFLICT")).thenReturn(true);

        reconciler.reconcileDueOrders();

        InOrder order = inOrder(stateService);
        order.verify(stateService).suspendVoucher(VOUCHER_ID, "DB_ORDER_ID_CONFLICT");
        order.verify(stateService).compensate(message(), "DB_ORDER_ID_CONFLICT");
    }

    @Test
    void anOrderAConsumerBatchIsPersistingIsLeftAlone() {
        // The claim script answers NOT_DUE while the consumer's claim lease holds.
        when(stateService.claimForReconciliation(message())).thenReturn(
                new SeckillOrderStateService.ReconciliationClaim(
                        SeckillOrderStateService.ReconciliationClaimDecision.NOT_DUE,
                        REDIS_NOW, REDIS_NOW - 10, 0L));

        reconciler.reconcileDueOrders();

        verifyNoInteractions(voucherOrderService);
        verify(stateService, never()).compensate(any(), anyString());
        verify(stateService, never()).markSuccess(any());
        verify(schedulingLock).unlock();
    }

    @Test
    void losingSchedulerInstanceCannotDeferTheWinningInstancesDueMember() {
        when(schedulingLock.tryLock()).thenReturn(false);

        reconciler.reconcileDueOrders();

        verify(stateService, never()).deferProcessingOrder(anyLong());
        verify(stateService, never()).claimForReconciliation(any());
        verifyNoInteractions(voucherOrderService);
        verify(schedulingLock, never()).unlock();
    }

    @Test
    void missingSnapshotIsLeftPendingAndDeferred() {
        when(stateService.find(ORDER_ID)).thenReturn(null);
        when(stateService.deferProcessingOrder(ORDER_ID)).thenReturn(true);

        reconciler.reconcileDueOrders();

        verifyNoInteractions(voucherOrderService);
        verify(redissonClient).getLock("lock:seckill:reconcile:" + ORDER_ID);
        verify(redissonClient, never()).getLock("lock:order:" + USER_ID);
        verify(schedulingLock).tryLock();
        verify(schedulingLock).unlock();
        verify(stateService).deferProcessingOrder(ORDER_ID);
        verify(stateService, never()).claimForReconciliation(any());
    }

    /** An unsafe state cannot heal itself; it stays visible (and deferred) for an operator. */
    @Test
    void unsafeClaimIsDeferredWithoutDatabaseAccess() {
        when(stateService.claimForReconciliation(any())).thenReturn(
                new SeckillOrderStateService.ReconciliationClaim(
                        RESERVATION_MISMATCH, REDIS_NOW, REDIS_NOW - 60, 0));
        when(stateService.deferProcessingOrder(ORDER_ID)).thenReturn(true);

        reconciler.reconcileDueOrders();

        verify(stateService).deferProcessingOrder(ORDER_ID);
        verify(stateService, never()).markSuccess(any());
        verify(stateService, never()).compensate(any(), anyString());
        verifyNoInteractions(voucherOrderService);
    }

    @Test
    void notificationFailureDoesNotUndoRepairedSuccess() {
        when(voucherOrderService.classifyPersistence(ORDER_ID, USER_ID, VOUCHER_ID))
                .thenReturn(SeckillOrderPersistenceResult.exact(ORDER_ID, USER_ID, VOUCHER_ID));
        when(stateService.markSuccess(message())).thenReturn(true);
        doThrow(new IllegalStateException("pubsub unavailable"))
                .when(webSocketNotifier).notify(USER_ID, true, ORDER_ID, VOUCHER_ID);

        reconciler.reconcileDueOrders();

        verify(stateService).markSuccess(message());
    }

    private static SeckillOrderStateService.ReconciliationClaim claim(long createdAt, long attempts) {
        return new SeckillOrderStateService.ReconciliationClaim(
                CLAIMED, REDIS_NOW, createdAt, attempts);
    }

    private static SeckillOrderMessage message() {
        return new SeckillOrderMessage(VOUCHER_ID, USER_ID, ORDER_ID);
    }
}
