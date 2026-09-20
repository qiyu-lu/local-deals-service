package com.localdeals.trade.mq;

import com.localdeals.trade.exception.BatchPersistDegradedException;
import com.localdeals.trade.exception.StockExhaustedException;
import com.localdeals.trade.service.IVoucherOrderService;
import com.localdeals.trade.service.SeckillOrderBatchPersister;
import com.localdeals.trade.service.SeckillOrderStateService;
import com.localdeals.platform.websocket.WebSocketNotifier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.annotation.DirtiesContext;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * End-to-end verification against a REAL RocketMQ broker that the seckill consumer's
 * failure-classification actually drives broker-level redelivery — not just the in-memory
 * decision covered by {@link SeckillOrderConsumerTest}.
 *
 * <p>Since M4 the topic is drained by the batch consumer, so the classification under test is
 * the batch one: a transient failure of the batch persister must reach the broker, and a slice
 * the fast path refuses must fall back to the single-message rules and be acknowledged.</p>
 *
 * <ul>
 *   <li><b>Transient failure</b> (generic exception out of the persister) → the batch is not
 *       acknowledged, so RocketMQ redelivers it and the persister sees the slice again. After
 *       retries exhaust ({@code maxReconsumeTimes}, default 16) RocketMQ routes it to the
 *       dead-letter topic {@code %DLQ%seckill-consumer-group}.</li>
 *   <li><b>Permanent failure</b> (the slice degrades, then {@link StockExhaustedException} is
 *       swallowed by the single-message path) → the batch ACKs, so the message is delivered
 *       exactly once and never retried.</li>
 * </ul>
 *
 * Uses voucher ids that do NOT exist in {@code tb_seckill_voucher}; if a background retry
 * message were ever redelivered to the real consumer after this test, it would throw
 * StockExhaustedException (no stock row) and be ACKed — no orphan data.
 */
@SpringBootTest(properties =
        // The seckill topic is drained by the batch consumer since M4.
        "local-deals.seckill.consume.enabled=true")
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class SeckillOrderRetryIT {

    @Autowired
    private RocketMQTemplate rocketMQTemplate;

    @MockitoBean
    private IVoucherOrderService voucherOrderService;

    @MockitoBean
    private WebSocketNotifier webSocketNotifier;

    @MockitoBean
    private SeckillOrderStateService seckillOrderStateService;

    @MockitoBean
    private SeckillOrderBatchPersister seckillOrderBatchPersister;

    private static final String TOPIC = System.getProperty(
            "m7rc.seckill.retry.topic", "seckill-order-topic");
    private static final long RUN_SUFFIX = System.currentTimeMillis() % 1_000_000L;
    private static final Long TRANSIENT_VOUCHER_ID = 77_000_000L + RUN_SUFFIX;
    private static final Long PERMANENT_VOUCHER_ID = TRANSIENT_VOUCHER_ID + 1L;

    @BeforeEach
    void allowExactProcessingReservation() {
        when(seckillOrderStateService.validateForConsumption(any()))
                .thenReturn(SeckillOrderStateService.ReservationDecision.PROCESS);
        // The batch claim and its finalize answer for whatever size the batch happens to have.
        when(seckillOrderStateService.claimForPersistence(any())).thenAnswer(invocation ->
                java.util.Collections.nCopies(
                        ((java.util.List<?>) invocation.getArgument(0)).size(),
                        SeckillOrderStateService.PersistClaim.CLAIMED));
        when(seckillOrderStateService.markSuccessBatch(any())).thenAnswer(invocation ->
                java.util.Collections.nCopies(
                        ((java.util.List<?>) invocation.getArgument(0)).size(), Boolean.TRUE));
    }

    @Test
    void transientFailure_isRedeliveredByBroker() {
        // Generic (transient) exception out of the persister → batch not acknowledged →
        // RocketMQ must redeliver.
        doThrow(new RuntimeException("simulated DB timeout"))
                .when(seckillOrderBatchPersister).persistGroup(
                        org.mockito.ArgumentMatchers.eq(TRANSIENT_VOUCHER_ID), any());

        SeckillOrderMessage msg = new SeckillOrderMessage(
                TRANSIENT_VOUCHER_ID, 770001L + RUN_SUFFIX, 990001L + RUN_SUFFIX);
        rocketMQTemplate.convertAndSend(TOPIC, msg);

        // The real broker must hand the slice to the persister at least twice (original +
        // >=1 retry). The first consumer-retry delay is ~10s, so allow 40s.
        verify(seckillOrderBatchPersister, timeout(40_000).atLeast(2))
                .persistGroup(org.mockito.ArgumentMatchers.eq(TRANSIENT_VOUCHER_ID), any());
    }

    @Test
    void permanentFailure_isNotRedelivered() {
        // The slice degrades, the single-message path runs and swallows StockExhausted (ACK)
        // → the message must NOT be retried.
        doThrow(new BatchPersistDegradedException("stock guard no longer holds"))
                .when(seckillOrderBatchPersister).persistGroup(
                        org.mockito.ArgumentMatchers.eq(PERMANENT_VOUCHER_ID), any());
        doThrow(new StockExhaustedException("DB stock exhausted"))
                .when(voucherOrderService).createPendingOrder(any());
        doNothing().when(webSocketNotifier).notify(any(), org.mockito.ArgumentMatchers.anyBoolean(), any(), any());

        SeckillOrderMessage msg = new SeckillOrderMessage(
                PERMANENT_VOUCHER_ID, 770002L + RUN_SUFFIX, 990002L + RUN_SUFFIX);
        org.mockito.Mockito.when(seckillOrderStateService.compensate(msg, "DB_STOCK_EXHAUSTED"))
                .thenReturn(true);
        rocketMQTemplate.convertAndSend(TOPIC, msg);

        // Wait past the first retry window (~10s) and assert THIS voucher's message was
        // consumed exactly once (matching by voucherId isolates it from any leftover
        // background retry of an unrelated message).
        verify(voucherOrderService, after(15_000).times(1))
                .createPendingOrder(argThat(o -> o != null &&
                        o.getVoucherId().equals(PERMANENT_VOUCHER_ID)));
    }
}
