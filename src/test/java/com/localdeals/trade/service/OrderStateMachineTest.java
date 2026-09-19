package com.localdeals.trade.service;

import com.localdeals.trade.entity.OrderEvent;
import com.localdeals.trade.entity.OrderStateLog;
import com.localdeals.trade.entity.OrderStatus;
import com.localdeals.trade.mapper.OrderStateLogMapper;
import com.localdeals.trade.mapper.TradeOrderMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.EnumSet;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OrderStateMachineTest {

    private TradeOrderMapper orderMapper;
    private OrderStateLogMapper logMapper;
    private OrderStateMachine stateMachine;

    @BeforeEach
    void setUp() {
        orderMapper = mock(TradeOrderMapper.class);
        logMapper = mock(OrderStateLogMapper.class);
        stateMachine = new OrderStateMachine(orderMapper, logMapper);
    }

    @Test
    void theGraphIsExactlyTheFiveDocumentedTransitions() {
        Set<String> edges = EnumSet.allOf(OrderEvent.class).stream()
                .map(event -> event.from() + "->" + event.to())
                .collect(Collectors.toSet());

        assertThat(edges).containsExactlyInAnyOrder(
                "PENDING_PAY->PAID",
                "PENDING_PAY->CLOSED",
                "PAID->USED",
                "PAID->REFUNDING",
                "REFUNDING->REFUNDED");
        assertThat(EnumSet.allOf(OrderEvent.class).stream().filter(OrderEvent::requiresExpiry))
                .containsExactly(OrderEvent.CLOSE);
        assertThat(EnumSet.allOf(OrderEvent.class).stream().filter(OrderEvent::releasesInventory))
                .containsExactlyInAnyOrder(OrderEvent.CLOSE, OrderEvent.REFUND_SUCCESS);
    }

    @Test
    void winningCompareAndSetWritesOneStateLogRow() {
        when(orderMapper.transition(42L, "PENDING_PAY", "PAID", false, false)).thenReturn(1);

        assertThat(stateMachine.fire(42L, OrderEvent.PAY, "CHANNEL")).isTrue();

        ArgumentCaptor<OrderStateLog> log = ArgumentCaptor.forClass(OrderStateLog.class);
        verify(logMapper).insert(log.capture());
        assertThat(log.getValue().getOrderNo()).isEqualTo(42L);
        assertThat(log.getValue().getFromStatus()).isEqualTo(OrderStatus.PENDING_PAY);
        assertThat(log.getValue().getToStatus()).isEqualTo(OrderStatus.PAID);
        assertThat(log.getValue().getEvent()).isEqualTo("PAY");
        assertThat(log.getValue().getOperator()).isEqualTo("CHANNEL");
    }

    @Test
    void losingCompareAndSetReturnsFalseAndLogsNothing() {
        when(orderMapper.transition(anyLong(), anyString(), anyString(), anyBoolean(), anyBoolean()))
                .thenReturn(0);

        assertThat(stateMachine.fire(42L, OrderEvent.CLOSE, "SYSTEM")).isFalse();

        verify(logMapper, never()).insert(any(OrderStateLog.class));
    }

    @Test
    void closePassesTheExpiryGuardAndTheInventoryReleaseFlag() {
        when(orderMapper.transition(7L, "PENDING_PAY", "CLOSED", true, true)).thenReturn(1);

        assertThat(stateMachine.fire(7L, OrderEvent.CLOSE, "SYSTEM")).isTrue();

        verify(orderMapper).transition(7L, "PENDING_PAY", "CLOSED", true, true);
    }

    @Test
    void creationIsLoggedWithoutASourceStatus() {
        stateMachine.recordCreated(9L, "SYSTEM");

        ArgumentCaptor<OrderStateLog> log = ArgumentCaptor.forClass(OrderStateLog.class);
        verify(logMapper).insert(log.capture());
        assertThat(log.getValue().getFromStatus()).isNull();
        assertThat(log.getValue().getToStatus()).isEqualTo(OrderStatus.PENDING_PAY);
        assertThat(log.getValue().getEvent()).isEqualTo(OrderStateLog.EVENT_CREATE);
    }
}
