package com.localdeals.mq;

import com.localdeals.exception.StockExhaustedException;
import com.localdeals.service.IVoucherOrderService;
import com.localdeals.service.SeckillOrderStateService;
import com.localdeals.websocket.WebSocketNotifier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
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
 * <ul>
 *   <li><b>Transient failure</b> (generic exception rethrown) → RocketMQ redelivers the
 *       message, so the consumer is invoked more than once. After retries exhaust
 *       ({@code maxReconsumeTimes}, default 16) RocketMQ routes it to the dead-letter
 *       topic {@code %DLQ%seckill-consumer-group}.</li>
 *   <li><b>Permanent failure</b> ({@link StockExhaustedException} swallowed) → the consumer
 *       ACKs, so the message is delivered exactly once and never retried.</li>
 * </ul>
 *
 * Uses voucher ids that do NOT exist in {@code tb_seckill_voucher}; if a background retry
 * message were ever redelivered to the real consumer after this test, it would throw
 * StockExhaustedException (no stock row) and be ACKed — no orphan data.
 */
@SpringBootTest(properties =
        "rocketmq.consumer.listeners[seckill-consumer-group][seckill-order-topic]=true")
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class SeckillOrderRetryIT {

    @Autowired
    private RocketMQTemplate rocketMQTemplate;

    @MockBean
    private IVoucherOrderService voucherOrderService;

    @MockBean
    private WebSocketNotifier webSocketNotifier;

    @MockBean
    private SeckillOrderStateService seckillOrderStateService;

    private static final String TOPIC = System.getProperty(
            "m7rc.seckill.retry.topic", "seckill-order-topic");
    private static final long RUN_SUFFIX = System.currentTimeMillis() % 1_000_000L;
    private static final Long TRANSIENT_VOUCHER_ID = 77_000_000L + RUN_SUFFIX;
    private static final Long PERMANENT_VOUCHER_ID = TRANSIENT_VOUCHER_ID + 1L;

    @BeforeEach
    void allowExactProcessingReservation() {
        when(seckillOrderStateService.validateForConsumption(any()))
                .thenReturn(SeckillOrderStateService.ReservationDecision.PROCESS);
    }

    @Test
    void transientFailure_isRedeliveredByBroker() {
        // Generic (transient) exception → consumer rethrows → RocketMQ must redeliver.
        doThrow(new RuntimeException("simulated DB timeout"))
                .when(voucherOrderService).createVoucherOrder(any());

        SeckillOrderMessage msg = new SeckillOrderMessage(
                TRANSIENT_VOUCHER_ID, 770001L + RUN_SUFFIX, 990001L + RUN_SUFFIX);
        rocketMQTemplate.convertAndSend(TOPIC, msg);

        // The real broker must invoke the consumer at least twice (original + >=1 retry)
        // for THIS voucher. First consumer-retry delay is ~10s, so allow 40s.
        verify(voucherOrderService, timeout(40_000).atLeast(2))
                .createVoucherOrder(argThat(o -> o != null &&
                        o.getVoucherId().equals(TRANSIENT_VOUCHER_ID)));
    }

    @Test
    void permanentFailure_isNotRedelivered() {
        // StockExhaustedException is swallowed (ACK) → message must NOT be retried.
        doThrow(new StockExhaustedException("DB stock exhausted"))
                .when(voucherOrderService).createVoucherOrder(any());
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
                .createVoucherOrder(argThat(o -> o != null &&
                        o.getVoucherId().equals(PERMANENT_VOUCHER_ID)));
    }
}
