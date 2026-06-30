package com.localdeals.mq;

import com.localdeals.exception.StockExhaustedException;
import com.localdeals.service.IVoucherOrderService;
import com.localdeals.websocket.WebSocketNotifier;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;

import javax.annotation.Resource;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@SpringBootTest
@ActiveProfiles("test")
class SeckillOrderConsumerIT {

    @Resource
    private SeckillOrderConsumer consumer;

    @MockBean
    private IVoucherOrderService voucherOrderService;

    @MockBean
    private WebSocketNotifier webSocketNotifier;

    @Test
    void onMessage_stockExhausted_doesNotThrowSoRocketMQAcks() {
        doThrow(new StockExhaustedException("DB stock exhausted. voucherId=88888"))
                .when(voucherOrderService).createVoucherOrder(any());

        SeckillOrderMessage msg = new SeckillOrderMessage();
        msg.setVoucherId(88888L);
        msg.setUserId(1L);
        msg.setOrderId(999L);

        // Must NOT throw — rethrowing causes RocketMQ to retry forever.
        assertThatCode(() -> consumer.onMessage(msg)).doesNotThrowAnyException();

        // Consumer must push failure notification to the user.
        verify(webSocketNotifier).notify(1L, false, 999L, 88888L);
    }

    @Test
    void onMessage_transientException_rethrowsForRetry() {
        doThrow(new RuntimeException("DB connection timeout"))
                .when(voucherOrderService).createVoucherOrder(any());

        SeckillOrderMessage msg = new SeckillOrderMessage();
        msg.setVoucherId(88888L);
        msg.setUserId(2L);
        msg.setOrderId(998L);

        // Transient errors MUST propagate so RocketMQ retries.
        assertThatCode(() -> consumer.onMessage(msg))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("DB connection timeout");
    }
}
