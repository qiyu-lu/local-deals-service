package com.localdeals.trade.mq;

import com.localdeals.platform.observability.TraceContext;
import com.localdeals.trade.service.OrderCloseService;
import com.localdeals.trade.service.OrderCloseService.Outcome;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OrderCloseConsumerTest {

    private final OrderCloseService closeService = mock(OrderCloseService.class);
    private final OrderCloseConsumer consumer = new OrderCloseConsumer(closeService);

    @Test
    void anOrderStillBeforeItsDeadlineIsRedeliveredLater() {
        when(closeService.closeIfExpired(5L, "SYSTEM")).thenReturn(Outcome.NOT_DUE);

        assertThatThrownBy(() -> consumer.onMessage(new OrderCloseMessage(5L)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void everySettledOutcomeIsAcknowledged() {
        for (Outcome outcome : new Outcome[]{Outcome.CLOSED, Outcome.ALREADY_CLOSED,
                Outcome.NOT_PENDING, Outcome.NOT_FOUND}) {
            when(closeService.closeIfExpired(6L, "SYSTEM")).thenReturn(outcome);

            assertThatCode(() -> consumer.onMessage(new OrderCloseMessage(6L))).doesNotThrowAnyException();
        }
    }

    /**
     * "Unpaid order closed" is the one line of the whole lifecycle written an hour after the
     * request that started it, on whichever instance happened to get the timer message. Under
     * the buyer's trace it joins that request; without one it joins nothing.
     */
    @Test
    void theCloseRunsUnderTheTraceOfTheRequestThatBoughtTheOrder() {
        AtomicReference<String> seen = new AtomicReference<>();
        when(closeService.closeIfExpired(7L, "SYSTEM")).thenAnswer(invocation -> {
            seen.set(TraceContext.current());
            return Outcome.CLOSED;
        });

        consumer.onMessage(new OrderCloseMessage(7L, "trace-of-the-buyer"));

        assertThat(seen.get()).isEqualTo("trace-of-the-buyer");
        assertThat(TraceContext.current()).isNull();
    }

    /** A timer message written before M8 has no trace; that is not a reason to refuse it. */
    @Test
    void aMessageWithoutATraceStillCloses() {
        when(closeService.closeIfExpired(8L, "SYSTEM")).thenReturn(Outcome.CLOSED);

        assertThatCode(() -> consumer.onMessage(new OrderCloseMessage(8L))).doesNotThrowAnyException();
    }

    @Test
    void aMalformedMessageIsDroppedWithoutTouchingOrders() {
        assertThatCode(() -> consumer.onMessage(new OrderCloseMessage(null))).doesNotThrowAnyException();
        assertThatCode(() -> consumer.onMessage(null)).doesNotThrowAnyException();

        verify(closeService, never()).closeIfExpired(anyLong(), anyString());
    }
}
