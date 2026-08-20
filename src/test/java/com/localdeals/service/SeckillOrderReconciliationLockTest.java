package com.localdeals.service;

import com.localdeals.config.SeckillProperties;
import com.localdeals.mq.SeckillOrderConsumer;
import com.localdeals.mq.SeckillOrderMessage;
import com.localdeals.websocket.WebSocketNotifier;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/** Deterministically proves that consumer persistence and reconciliation share one lock. */
@ExtendWith(MockitoExtension.class)
class SeckillOrderReconciliationLockTest {

    private static final Long ORDER_ID = 90071992547409931L;
    private static final Long USER_ID = 23L;
    private static final Long VOUCHER_ID = 17L;

    @Mock
    private IVoucherOrderService voucherOrderService;
    @Mock
    private SeckillOrderStateService stateService;
    @Mock
    private RedissonClient redissonClient;
    @Mock
    private RLock sharedLock;
    @Mock
    private RLock schedulingLock;
    @Mock
    private WebSocketNotifier webSocketNotifier;

    private SeckillOrderConsumer consumer;
    private SeckillOrderReconciler reconciler;

    @BeforeEach
    void setUp() {
        ReentrantLock lockDelegate = new ReentrantLock();
        lenient().when(redissonClient.getLock("lock:order:" + USER_ID)).thenReturn(sharedLock);
        lenient().when(sharedLock.tryLock()).thenAnswer(invocation -> lockDelegate.tryLock());
        lenient().when(sharedLock.isHeldByCurrentThread())
                .thenAnswer(invocation -> lockDelegate.isHeldByCurrentThread());
        lenient().doAnswer(invocation -> {
            lockDelegate.unlock();
            return null;
        }).when(sharedLock).unlock();
        lenient().when(redissonClient.getLock("lock:seckill:reconcile:" + ORDER_ID))
                .thenReturn(schedulingLock);
        lenient().when(schedulingLock.tryLock()).thenReturn(true);
        lenient().when(schedulingLock.isHeldByCurrentThread()).thenReturn(true);

        consumer = new SeckillOrderConsumer();
        ReflectionTestUtils.setField(consumer, "voucherOrderService", voucherOrderService);
        ReflectionTestUtils.setField(consumer, "seckillOrderStateService", stateService);
        ReflectionTestUtils.setField(consumer, "redissonClient", redissonClient);
        ReflectionTestUtils.setField(consumer, "webSocketNotifier", webSocketNotifier);
        ReflectionTestUtils.setField(consumer, "meterRegistry", new SimpleMeterRegistry());
        ReflectionTestUtils.invokeMethod(consumer, "registerMetrics");

        SeckillProperties properties = new SeckillProperties();
        properties.getReconciliation().setEnabled(true);
        reconciler = new SeckillOrderReconciler(
                stateService, voucherOrderService, redissonClient, webSocketNotifier,
                properties, new SimpleMeterRegistry());
    }

    @Test
    void reconcilerCannotClassifyOrCompensateWhileConsumerPersistsUnderSharedLock() throws Exception {
        SeckillOrderMessage message = new SeckillOrderMessage(VOUCHER_ID, USER_ID, ORDER_ID);
        CountDownLatch consumerEnteredDatabaseWrite = new CountDownLatch(1);
        CountDownLatch releaseConsumer = new CountDownLatch(1);

        when(stateService.validateForConsumption(message))
                .thenReturn(SeckillOrderStateService.ReservationDecision.PROCESS);
        when(stateService.markSuccess(message)).thenReturn(true);
        when(stateService.findDueOrderIds(100)).thenReturn(Collections.singletonList(ORDER_ID));
        when(stateService.find(ORDER_ID)).thenReturn(new SeckillOrderStateService.Snapshot(
                ORDER_ID, USER_ID, VOUCHER_ID,
                SeckillOrderStateService.STATUS_PROCESSING, null));
        when(stateService.deferProcessingOrder(ORDER_ID)).thenReturn(true);
        doAnswer(invocation -> {
            consumerEnteredDatabaseWrite.countDown();
            if (!releaseConsumer.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("test did not release consumer");
            }
            return null;
        }).when(voucherOrderService).createVoucherOrder(any());

        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<?> consumerFuture = null;
        try {
            consumerFuture = executor.submit(() -> consumer.onMessage(message));
            assertThat(consumerEnteredDatabaseWrite.await(5, TimeUnit.SECONDS)).isTrue();

            reconciler.reconcileDueOrders();

            verify(sharedLock, times(2)).tryLock();
            verify(sharedLock, never()).unlock();
            verify(stateService, never()).claimForReconciliation(any());
            verify(stateService).deferProcessingOrder(ORDER_ID);
            verify(voucherOrderService, never()).classifyPersistence(any(), any(), any());
            verify(stateService, never()).compensate(any(), anyString());
            verify(stateService, never()).markSuccess(message);
            verify(schedulingLock).unlock();

            releaseConsumer.countDown();
            consumerFuture.get(5, TimeUnit.SECONDS);

            verify(stateService).markSuccess(message);
            verify(webSocketNotifier).notify(USER_ID, true, ORDER_ID, VOUCHER_ID);
            verify(sharedLock).unlock();
        } finally {
            releaseConsumer.countDown();
            if (consumerFuture != null && !consumerFuture.isDone()) {
                consumerFuture.cancel(true);
            }
            executor.shutdownNow();
        }
    }

    @Test
    void losingReconcilerCannotMoveScoreBeforeWinnerClaims() throws Exception {
        SeckillOrderMessage message = new SeckillOrderMessage(VOUCHER_ID, USER_ID, ORDER_ID);
        ReentrantLock schedulerDelegate = new ReentrantLock();
        CountDownLatch winnerOwnsSchedulingLock = new CountDownLatch(1);
        CountDownLatch releaseWinner = new CountDownLatch(1);
        AtomicBoolean firstUserLockLookup = new AtomicBoolean(true);

        when(schedulingLock.tryLock()).thenAnswer(invocation -> schedulerDelegate.tryLock());
        when(schedulingLock.isHeldByCurrentThread())
                .thenAnswer(invocation -> schedulerDelegate.isHeldByCurrentThread());
        doAnswer(invocation -> {
            schedulerDelegate.unlock();
            return null;
        }).when(schedulingLock).unlock();
        when(redissonClient.getLock("lock:order:" + USER_ID)).thenAnswer(invocation -> {
            if (firstUserLockLookup.compareAndSet(true, false)) {
                winnerOwnsSchedulingLock.countDown();
                if (!releaseWinner.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("test did not release reconciliation winner");
                }
            }
            return sharedLock;
        });
        when(stateService.findDueOrderIds(100)).thenReturn(Collections.singletonList(ORDER_ID));
        when(stateService.find(ORDER_ID)).thenReturn(new SeckillOrderStateService.Snapshot(
                ORDER_ID, USER_ID, VOUCHER_ID,
                SeckillOrderStateService.STATUS_PROCESSING, null));
        when(stateService.claimForReconciliation(message)).thenReturn(
                new SeckillOrderStateService.ReconciliationClaim(
                        SeckillOrderStateService.ReconciliationClaimDecision.CLAIMED,
                        10_000L, 9_000L, 1L));
        when(voucherOrderService.classifyPersistence(ORDER_ID, USER_ID, VOUCHER_ID))
                .thenReturn(com.localdeals.dto.SeckillOrderPersistenceResult.exact(
                        ORDER_ID, USER_ID, VOUCHER_ID));
        when(stateService.markSuccess(message)).thenReturn(true);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        Future<?> winner = null;
        Future<?> loser = null;
        try {
            winner = executor.submit(reconciler::reconcileDueOrders);
            assertThat(winnerOwnsSchedulingLock.await(5, TimeUnit.SECONDS)).isTrue();

            loser = executor.submit(reconciler::reconcileDueOrders);
            loser.get(5, TimeUnit.SECONDS);

            verify(stateService, never()).deferProcessingOrder(anyLong());
            verify(stateService, never()).claimForReconciliation(any());
            verify(voucherOrderService, never()).classifyPersistence(any(), any(), any());

            releaseWinner.countDown();
            winner.get(5, TimeUnit.SECONDS);

            verify(schedulingLock, times(2)).tryLock();
            verify(stateService, times(1)).claimForReconciliation(message);
            verify(voucherOrderService, times(1))
                    .classifyPersistence(ORDER_ID, USER_ID, VOUCHER_ID);
            verify(stateService, times(1)).markSuccess(message);
            verify(stateService, never()).deferProcessingOrder(anyLong());
        } finally {
            releaseWinner.countDown();
            if (winner != null && !winner.isDone()) {
                winner.cancel(true);
            }
            if (loser != null && !loser.isDone()) {
                loser.cancel(true);
            }
            executor.shutdownNow();
        }
    }
}
