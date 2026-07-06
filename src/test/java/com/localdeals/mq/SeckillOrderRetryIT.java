package com.localdeals.mq;

import com.localdeals.exception.StockExhaustedException;
import com.localdeals.service.IVoucherOrderService;
import com.localdeals.websocket.WebSocketNotifier;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * End-to-end verification against a REAL RocketMQ broker that the seckill consumer's
 * failure-classification actually drives broker-level redelivery — not just the in-memory
 * decision covered by {@link SeckillOrderConsumerIT}.
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
@SpringBootTest
@ActiveProfiles("test")
class SeckillOrderRetryIT {

    @Autowired
    private RocketMQTemplate rocketMQTemplate;

    @MockBean
    private IVoucherOrderService voucherOrderService;

    @MockBean
    private WebSocketNotifier webSocketNotifier;

    private static final String TOPIC = "seckill-order-topic";

    @Test
    void transientFailure_isRedeliveredByBroker() {
        // Generic (transient) exception → consumer rethrows → RocketMQ must redeliver.
        doThrow(new RuntimeException("simulated DB timeout"))
                .when(voucherOrderService).createVoucherOrder(any());

        SeckillOrderMessage msg = new SeckillOrderMessage(77771L, 770001L, 990001L);
        rocketMQTemplate.convertAndSend(TOPIC, msg);

        // The real broker must invoke the consumer at least twice (original + >=1 retry)
        // for THIS voucher. First consumer-retry delay is ~10s, so allow 40s.
        verify(voucherOrderService, timeout(40_000).atLeast(2))
                .createVoucherOrder(argThat(o -> o != null && o.getVoucherId().equals(77771L)));
    }

    @Test
    void permanentFailure_isNotRedelivered() {
        // StockExhaustedException is swallowed (ACK) → message must NOT be retried.
        doThrow(new StockExhaustedException("DB stock exhausted"))
                .when(voucherOrderService).createVoucherOrder(any());
        doNothing().when(webSocketNotifier).notify(any(), org.mockito.ArgumentMatchers.anyBoolean(), any(), any());

        SeckillOrderMessage msg = new SeckillOrderMessage(77772L, 770002L, 990002L);
        rocketMQTemplate.convertAndSend(TOPIC, msg);

        // Wait past the first retry window (~10s) and assert THIS voucher's message was
        // consumed exactly once (matching by voucherId isolates it from any leftover
        // background retry of an unrelated message).
        verify(voucherOrderService, after(15_000).times(1))
                .createVoucherOrder(argThat(o -> o != null && o.getVoucherId().equals(77772L)));
    }
}
