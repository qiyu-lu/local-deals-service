package com.localdeals.service;

import com.localdeals.config.SeckillProperties;
import com.localdeals.dto.SeckillOrderPersistenceResult;
import com.localdeals.mq.SeckillOrderMessage;
import com.localdeals.websocket.WebSocketNotifier;
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

import static com.localdeals.service.SeckillOrderStateService.ReconciliationClaimDecision.CLAIMED;
import static com.localdeals.service.SeckillOrderStateService.ReconciliationClaimDecision.RESERVATION_MISMATCH;
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
    private RLock lock;
    @Mock
    private RLock schedulingLock;
    @Mock
    private WebSocketNotifier webSocketNotifier;

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
                properties, new SimpleMeterRegistry());

        lenient().when(stateService.findDueOrderIds(100))
                .thenReturn(Collections.singletonList(ORDER_ID));
        lenient().when(stateService.find(ORDER_ID)).thenReturn(
                new SeckillOrderStateService.Snapshot(
                        ORDER_ID, USER_ID, VOUCHER_ID,
                        SeckillOrderStateService.STATUS_PROCESSING, null));
        lenient().when(redissonClient.getLock("lock:order:" + USER_ID)).thenReturn(lock);
        lenient().when(redissonClient.getLock("lock:seckill:reconcile:" + ORDER_ID))
                .thenReturn(schedulingLock);
        lenient().when(schedulingLock.tryLock()).thenReturn(true);
        lenient().when(schedulingLock.isHeldByCurrentThread()).thenReturn(true);
        lenient().when(lock.tryLock()).thenReturn(true);
        lenient().when(lock.isHeldByCurrentThread()).thenReturn(true);
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

        InOrder order = inOrder(schedulingLock, lock, stateService, voucherOrderService);
        order.verify(schedulingLock).tryLock();
        order.verify(lock).tryLock();
        order.verify(stateService).claimForReconciliation(message());
        order.verify(voucherOrderService).classifyPersistence(ORDER_ID, USER_ID, VOUCHER_ID);
        order.verify(stateService).markSuccess(message());
        order.verify(lock).isHeldByCurrentThread();
        order.verify(lock).unlock();
        order.verify(schedulingLock).isHeldByCurrentThread();
        order.verify(schedulingLock).unlock();
        verify(stateService, never()).compensate(any(), anyString());
        verify(webSocketNotifier).notify(USER_ID, true, ORDER_ID, VOUCHER_ID);
    }

    @Test
    void absentOrderBeforeHardDeadlineIsDeferred() {
        when(voucherOrderService.classifyPersistence(ORDER_ID, USER_ID, VOUCHER_ID))
                .thenReturn(SeckillOrderPersistenceResult.absent());
        when(stateService.claimForReconciliation(any())).thenReturn(
                claim(REDIS_NOW - Duration.ofMinutes(14).getSeconds(), 4));

        reconciler.reconcileDueOrders();

        verify(stateService, never()).compensate(any(), anyString());
        verifyNoInteractions(webSocketNotifier);
    }

    @Test
    void absentOrderAtHardDeadlineRemainsProcessingWhenCompensationDisabled() {
        when(voucherOrderService.classifyPersistence(ORDER_ID, USER_ID, VOUCHER_ID))
                .thenReturn(SeckillOrderPersistenceResult.absent());
        when(stateService.claimForReconciliation(any())).thenReturn(
                claim(REDIS_NOW - Duration.ofMinutes(15).getSeconds(), 8));

        reconciler.reconcileDueOrders();

        verify(stateService, never()).compensate(any(), anyString());
        verify(stateService, never()).quarantineProcessingOrder(anyLong(), anyString());
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
        verify(stateService, never()).quarantineProcessingOrder(anyLong(), anyString());
    }

    @Test
    void pairConflictSuspendsButDoesNotCompensateWhenFlagIsOff() {
        when(voucherOrderService.classifyPersistence(ORDER_ID, USER_ID, VOUCHER_ID))
                .thenReturn(SeckillOrderPersistenceResult.userVoucherConflict(777L, USER_ID, VOUCHER_ID));

        reconciler.reconcileDueOrders();

        verify(stateService).suspendVoucher(VOUCHER_ID, "DB_ORDER_CONFLICT");
        verify(stateService, never()).compensate(any(), anyString());
        verify(stateService, never()).quarantineProcessingOrder(anyLong(), anyString());
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
    void orderIdConflictAlwaysSuspendsAndQuarantinesWithoutCompensation() {
        properties.getReconciliation().setCompensationEnabled(true);
        when(voucherOrderService.classifyPersistence(ORDER_ID, USER_ID, VOUCHER_ID))
                .thenReturn(SeckillOrderPersistenceResult.orderIdConflict(ORDER_ID, 99L, 88L));
        when(stateService.quarantineProcessingOrder(ORDER_ID, "DB_ORDER_ID_CONFLICT"))
                .thenReturn(true);

        reconciler.reconcileDueOrders();

        InOrder order = inOrder(stateService);
        order.verify(stateService).suspendVoucher(VOUCHER_ID, "DB_ORDER_ID_CONFLICT");
        order.verify(stateService).quarantineProcessingOrder(ORDER_ID, "DB_ORDER_ID_CONFLICT");
        verify(stateService, never()).compensate(any(), anyString());
    }

    @Test
    void busyConsumerLockSkipsClaimAndDatabase() {
        when(lock.tryLock()).thenReturn(false);
        when(stateService.deferProcessingOrder(ORDER_ID)).thenReturn(true);

        reconciler.reconcileDueOrders();

        verify(stateService, never()).claimForReconciliation(any());
        verify(stateService).deferProcessingOrder(ORDER_ID);
        verifyNoInteractions(voucherOrderService);
        verify(lock, never()).unlock();
        verify(schedulingLock).unlock();
    }

    @Test
    void losingSchedulerInstanceCannotDeferTheWinningInstancesDueMember() {
        when(schedulingLock.tryLock()).thenReturn(false);

        reconciler.reconcileDueOrders();

        verify(stateService, never()).deferProcessingOrder(anyLong());
        verify(stateService, never()).claimForReconciliation(any());
        verifyNoInteractions(voucherOrderService);
        verify(lock, never()).tryLock();
        verify(schedulingLock, never()).unlock();
    }

    @Test
    void missingSnapshotIsLeftPendingWithoutAnOutOfLockQuarantine() {
        when(stateService.find(ORDER_ID)).thenReturn(null);
        when(stateService.deferProcessingOrder(ORDER_ID)).thenReturn(true);

        reconciler.reconcileDueOrders();

        verifyNoInteractions(voucherOrderService);
        verify(redissonClient).getLock("lock:seckill:reconcile:" + ORDER_ID);
        verify(redissonClient, never()).getLock("lock:order:" + USER_ID);
        verify(schedulingLock).tryLock();
        verify(schedulingLock).unlock();
        verify(stateService).deferProcessingOrder(ORDER_ID);
        verify(stateService, never()).quarantineProcessingOrder(anyLong(), anyString());
        verify(stateService, never()).claimForReconciliation(any());
    }

    @Test
    void unsafeClaimIsQuarantinedWithoutDatabaseAccess() {
        when(stateService.claimForReconciliation(any())).thenReturn(
                new SeckillOrderStateService.ReconciliationClaim(
                        RESERVATION_MISMATCH, REDIS_NOW, REDIS_NOW - 60, 0));
        when(stateService.quarantineProcessingOrder(
                ORDER_ID, "CLAIM_RESERVATION_MISMATCH")).thenReturn(true);

        reconciler.reconcileDueOrders();

        verify(stateService).quarantineProcessingOrder(
                ORDER_ID, "CLAIM_RESERVATION_MISMATCH");
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
        verify(lock).unlock();
    }

    private static SeckillOrderStateService.ReconciliationClaim claim(long createdAt, long attempts) {
        return new SeckillOrderStateService.ReconciliationClaim(
                CLAIMED, REDIS_NOW, createdAt, attempts);
    }

    private static SeckillOrderMessage message() {
        return new SeckillOrderMessage(VOUCHER_ID, USER_ID, ORDER_ID);
    }
}
