package com.localdeals.mq;

import com.localdeals.exception.OrderReservationConflictException;
import com.localdeals.exception.OrderIdConflictException;
import com.localdeals.exception.StockExhaustedException;
import com.localdeals.service.IVoucherOrderService;
import com.localdeals.service.SeckillOrderStateService;
import com.localdeals.websocket.WebSocketNotifier;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SeckillOrderConsumerTest {

    @InjectMocks
    private SeckillOrderConsumer consumer;

    @Mock
    private IVoucherOrderService voucherOrderService;

    @Mock
    private WebSocketNotifier webSocketNotifier;

    @Mock
    private SeckillOrderStateService seckillOrderStateService;

    @Mock
    private RedissonClient redissonClient;

    @Mock
    private RLock lock;

    @BeforeEach
    void allowExactProcessingReservation() {
        ReflectionTestUtils.setField(consumer, "meterRegistry", new SimpleMeterRegistry());
        ReflectionTestUtils.invokeMethod(consumer, "registerMetrics");
        lenient().when(redissonClient.getLock(any())).thenReturn(lock);
        lenient().when(lock.tryLock()).thenReturn(true);
        lenient().when(seckillOrderStateService.validateForConsumption(any()))
                .thenReturn(SeckillOrderStateService.ReservationDecision.PROCESS);
    }

    @Test
    void onMessage_stockExhausted_doesNotThrowSoRocketMQAcks() {
        doThrow(new StockExhaustedException("DB stock exhausted. voucherId=88888"))
                .when(voucherOrderService).createVoucherOrder(any());

        SeckillOrderMessage msg = new SeckillOrderMessage();
        msg.setVoucherId(88888L);
        msg.setUserId(1L);
        msg.setOrderId(999L);
        when(seckillOrderStateService.compensate(msg, "DB_STOCK_EXHAUSTED")).thenReturn(true);

        // ACK only after the exact Redis reservation has been compensated.
        assertThatCode(() -> consumer.onMessage(msg)).doesNotThrowAnyException();

        verify(seckillOrderStateService).suspendVoucher(88888L, "DB_STOCK_EXHAUSTED");
        verify(seckillOrderStateService).compensate(msg, "DB_STOCK_EXHAUSTED");
        verify(webSocketNotifier).notify(1L, false, 999L, 88888L);
    }

    @Test
    void onMessage_compensationUnavailable_rethrowsForRetry() {
        doThrow(new StockExhaustedException("DB stock exhausted. voucherId=88888"))
                .when(voucherOrderService).createVoucherOrder(any());
        SeckillOrderMessage msg = new SeckillOrderMessage(88888L, 3L, 997L);
        doThrow(new RuntimeException("redis unavailable"))
                .when(seckillOrderStateService).suspendVoucher(88888L, "DB_STOCK_EXHAUSTED");

        assertThatCode(() -> consumer.onMessage(msg))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("redis unavailable");

        verify(webSocketNotifier, never()).notify(3L, false, 997L, 88888L);
    }

    @Test
    void onMessage_differentOrderForExistingPurchaseCompensatesAndAcks() {
        doThrow(new OrderReservationConflictException("different persisted order"))
                .when(voucherOrderService).createVoucherOrder(any());
        SeckillOrderMessage msg = new SeckillOrderMessage(88888L, 5L, 995L);
        when(seckillOrderStateService.compensate(msg, "DB_ORDER_CONFLICT")).thenReturn(true);

        assertThatCode(() -> consumer.onMessage(msg)).doesNotThrowAnyException();

        verify(seckillOrderStateService).suspendVoucher(88888L, "DB_ORDER_CONFLICT");
        verify(seckillOrderStateService).compensate(msg, "DB_ORDER_CONFLICT");
        verify(webSocketNotifier).notify(5L, false, 995L, 88888L);
    }

    @Test
    void onMessage_orderIdCollisionSuspendsAndQuarantinesWithoutCompensation() {
        doThrow(new OrderIdConflictException("id belongs to another order"))
                .when(voucherOrderService).createVoucherOrder(any());
        SeckillOrderMessage msg = new SeckillOrderMessage(88888L, 15L, 985L);
        when(seckillOrderStateService.quarantineProcessingOrder(
                985L, "DB_ORDER_ID_CONFLICT")).thenReturn(true);

        assertThatCode(() -> consumer.onMessage(msg))
                .isInstanceOf(OrderIdConflictException.class)
                .hasMessageContaining("another order");

        InOrder order = inOrder(seckillOrderStateService);
        order.verify(seckillOrderStateService)
                .suspendVoucher(88888L, "DB_ORDER_ID_CONFLICT");
        order.verify(seckillOrderStateService)
                .quarantineProcessingOrder(985L, "DB_ORDER_ID_CONFLICT");
        verify(seckillOrderStateService, never()).compensate(any(), anyString());
        verify(seckillOrderStateService, never()).markSuccess(any());
        verifyNoInteractions(webSocketNotifier);
    }

    @Test
    void onMessage_successFinalizesDurableStateBeforeBestEffortNotification() {
        SeckillOrderMessage msg = new SeckillOrderMessage(88888L, 4L, 996L);
        when(seckillOrderStateService.markSuccess(msg)).thenReturn(true);
        doThrow(new RuntimeException("pubsub unavailable"))
                .when(webSocketNotifier).notify(4L, true, 996L, 88888L);

        assertThatCode(() -> consumer.onMessage(msg)).doesNotThrowAnyException();

        InOrder inOrder = inOrder(seckillOrderStateService, voucherOrderService);
        inOrder.verify(seckillOrderStateService).validateForConsumption(msg);
        inOrder.verify(voucherOrderService).createVoucherOrder(any());
        inOrder.verify(seckillOrderStateService).markSuccess(msg);
        verify(webSocketNotifier).notify(4L, true, 996L, 88888L);
    }

    @Test
    void onMessage_poisonedOwnership_retriesWithoutDatabaseMutation() {
        SeckillOrderMessage msg = new SeckillOrderMessage(88888L, 6L, 994L);
        when(seckillOrderStateService.validateForConsumption(msg))
                .thenReturn(SeckillOrderStateService.ReservationDecision.POISONED);

        assertThatCode(() -> consumer.onMessage(msg))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ownership mismatch");

        verify(voucherOrderService, never()).createVoucherOrder(any());
        verify(seckillOrderStateService, never()).markSuccess(any());
        verifyNoInteractions(webSocketNotifier);
    }

    @Test
    void onMessage_terminalRedisState_acknowledgesWithoutDatabaseMutation() {
        SeckillOrderMessage successful = new SeckillOrderMessage(88888L, 7L, 993L);
        when(seckillOrderStateService.validateForConsumption(successful))
                .thenReturn(SeckillOrderStateService.ReservationDecision.ALREADY_SUCCESS);

        assertThatCode(() -> consumer.onMessage(successful)).doesNotThrowAnyException();

        verify(voucherOrderService, never()).createVoucherOrder(any());
        verify(seckillOrderStateService, never()).markSuccess(any());
        verifyNoInteractions(webSocketNotifier);
    }

    @Test
    void onMessage_failedRedisState_acknowledgesWithoutDatabaseMutation() {
        SeckillOrderMessage failed = new SeckillOrderMessage(88888L, 8L, 992L);
        when(seckillOrderStateService.validateForConsumption(failed))
                .thenReturn(SeckillOrderStateService.ReservationDecision.ALREADY_FAILED);

        assertThatCode(() -> consumer.onMessage(failed)).doesNotThrowAnyException();

        verify(voucherOrderService, never()).createVoucherOrder(any());
        verify(seckillOrderStateService, never()).markSuccess(any());
        verifyNoInteractions(webSocketNotifier);
    }

    @Test
    void onMessage_missingRedisState_retriesWithoutDatabaseMutation() {
        SeckillOrderMessage msg = new SeckillOrderMessage(88888L, 9L, 991L);
        when(seckillOrderStateService.validateForConsumption(msg))
                .thenReturn(SeckillOrderStateService.ReservationDecision.RETRYABLE_STATE_MISSING);

        assertThatCode(() -> consumer.onMessage(msg))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("missing or incomplete");

        verify(voucherOrderService, never()).createVoucherOrder(any());
        verify(seckillOrderStateService, never()).markSuccess(any());
        verifyNoInteractions(webSocketNotifier);
    }

    @Test
    void onMessage_redisValidationFailure_retriesWithoutDatabaseMutation() {
        SeckillOrderMessage msg = new SeckillOrderMessage(88888L, 11L, 989L);
        when(seckillOrderStateService.validateForConsumption(msg))
                .thenThrow(new RuntimeException("redis unavailable"));

        assertThatCode(() -> consumer.onMessage(msg))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("redis unavailable");

        verify(voucherOrderService, never()).createVoucherOrder(any());
        verify(seckillOrderStateService, never()).markSuccess(any());
        verifyNoInteractions(webSocketNotifier);
    }

    @Test
    void onMessage_databaseCommittedButSuccessMarkFailed_retriesForIdempotentReplay() {
        SeckillOrderMessage msg = new SeckillOrderMessage(88888L, 12L, 988L);
        when(seckillOrderStateService.markSuccess(msg)).thenReturn(false);

        assertThatCode(() -> consumer.onMessage(msg))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("could not be marked SUCCESS");

        verify(voucherOrderService).createVoucherOrder(any());
        verify(seckillOrderStateService).markSuccess(msg);
        verifyNoInteractions(webSocketNotifier);
    }

    @Test
    void onMessage_malformedMessage_retriesWithoutCallingBusinessDependencies() {
        SeckillOrderMessage malformed = new SeckillOrderMessage(null, 10L, 990L);

        assertThatCode(() -> consumer.onMessage(malformed))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Malformed");

        verifyNoInteractions(voucherOrderService, webSocketNotifier);
        verify(seckillOrderStateService, never()).validateForConsumption(any());
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
