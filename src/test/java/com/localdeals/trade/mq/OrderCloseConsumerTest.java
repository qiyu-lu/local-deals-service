package com.localdeals.trade.mq;

import com.localdeals.trade.service.OrderCloseService;
import com.localdeals.trade.service.OrderCloseService.Outcome;
import org.junit.jupiter.api.Test;

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

    @Test
    void aMalformedMessageIsDroppedWithoutTouchingOrders() {
        assertThatCode(() -> consumer.onMessage(new OrderCloseMessage(null))).doesNotThrowAnyException();
        assertThatCode(() -> consumer.onMessage(null)).doesNotThrowAnyException();

        verify(closeService, never()).closeIfExpired(anyLong(), anyString());
    }
}
