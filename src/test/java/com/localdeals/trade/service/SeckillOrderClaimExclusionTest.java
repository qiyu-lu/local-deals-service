package com.localdeals.trade.service;

import com.localdeals.platform.websocket.WebSocketNotifier;
import com.localdeals.trade.config.SeckillProperties;
import com.localdeals.trade.mq.SeckillOrderProducer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;

import java.util.Collections;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Since M4 the consumer and the reconciler no longer share a Redisson lock: the consumer claims
 * the reservation in Redis, and the claim script answers NOT_DUE while that lease holds.
 */
@ExtendWith(MockitoExtension.class)
class SeckillOrderClaimExclusionTest {

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
    private RLock schedulingLock;
    @Mock
    private WebSocketNotifier webSocketNotifier;

    private SeckillOrderReconciler reconciler;

    @BeforeEach
    void setUp() {
        lenient().when(redissonClient.getLock("lock:seckill:reconcile:" + ORDER_ID))
                .thenReturn(schedulingLock);
        lenient().when(schedulingLock.tryLock()).thenReturn(true);
        lenient().when(schedulingLock.isHeldByCurrentThread()).thenReturn(true);

        SeckillProperties properties = new SeckillProperties();
        properties.getReconciliation().setEnabled(true);
        reconciler = new SeckillOrderReconciler(
                stateService, voucherOrderService, redissonClient, webSocketNotifier,
                org.mockito.Mockito.mock(SeckillOrderProducer.class),
                properties, new SimpleMeterRegistry());
    }

    @Test
    void aClaimedOrderIsNeitherClassifiedNorCompensated() {
        when(stateService.findDueOrderIds(100)).thenReturn(Collections.singletonList(ORDER_ID));
        when(stateService.find(ORDER_ID)).thenReturn(new SeckillOrderStateService.Snapshot(
                ORDER_ID, USER_ID, VOUCHER_ID, SeckillOrderStateService.STATUS_PROCESSING, null));
        // The consumer batch holds the lease, so the Lua answers NOT_DUE.
        when(stateService.claimForReconciliation(any())).thenReturn(
                new SeckillOrderStateService.ReconciliationClaim(
                        SeckillOrderStateService.ReconciliationClaimDecision.NOT_DUE,
                        1770000100L, 1770000000L, 0L));

        reconciler.reconcileDueOrders();

        verifyNoInteractions(voucherOrderService);
        verify(stateService, never()).compensate(any(), any());
        verify(stateService, never()).markSuccess(any());
    }

    @Test
    void theSharedPerUserLockIsGone() {
        when(stateService.findDueOrderIds(100)).thenReturn(Collections.singletonList(ORDER_ID));
        when(stateService.find(ORDER_ID)).thenReturn(new SeckillOrderStateService.Snapshot(
                ORDER_ID, USER_ID, VOUCHER_ID, SeckillOrderStateService.STATUS_PROCESSING, null));
        when(stateService.claimForReconciliation(any())).thenReturn(
                new SeckillOrderStateService.ReconciliationClaim(
                        SeckillOrderStateService.ReconciliationClaimDecision.NOT_DUE,
                        1770000100L, 1770000000L, 0L));

        reconciler.reconcileDueOrders();

        verify(redissonClient, never()).getLock(startsWith("lock:order:"));
    }
}
