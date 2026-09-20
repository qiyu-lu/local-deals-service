package com.localdeals.trade.service;

import com.localdeals.trade.config.OrderProperties;
import com.localdeals.trade.entity.OrderStateLog;
import com.localdeals.trade.exception.BatchPersistDegradedException;
import com.localdeals.trade.mapper.OrderStateLogMapper;
import com.localdeals.trade.mapper.SeckillVoucherMapper;
import com.localdeals.trade.mapper.TradeOrderMapper;
import com.localdeals.trade.mapper.VoucherMapper;
import com.localdeals.trade.mapper.VoucherMapper.VoucherSnapshot;
import com.localdeals.trade.mapper.TradeOrderMapper.SeckillOrderRow;
import com.localdeals.trade.mq.SeckillOrderMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The batch fast path: one multi-row INSERT IGNORE and exactly one stock UPDATE per voucher,
 * which is what removes the per-order hot-row queueing measured in M0 (D4).
 */
class SeckillOrderBatchPersisterTest {

    private TradeOrderMapper tradeOrderMapper;
    private SeckillVoucherMapper seckillVoucherMapper;
    private OrderStateLogMapper stateLogMapper;
    private VoucherMapper voucherMapper;
    private SeckillOrderBatchPersister persister;

    private static final VoucherSnapshot VOUCHER = new VoucherSnapshot(7L, 5L, 9L, 990L, 30);

    @BeforeEach
    void setUp() {
        tradeOrderMapper = mock(TradeOrderMapper.class);
        seckillVoucherMapper = mock(SeckillVoucherMapper.class);
        stateLogMapper = mock(OrderStateLogMapper.class);
        voucherMapper = mock(VoucherMapper.class);
        when(voucherMapper.selectSnapshot(anyLong())).thenReturn(VOUCHER);
        OrderProperties orderProperties = new OrderProperties();
        persister = new SeckillOrderBatchPersister(
                tradeOrderMapper, seckillVoucherMapper, stateLogMapper, voucherMapper, orderProperties);
    }

    @Test
    void oneStockUpdatePerVoucherCarriesTheWholeBatch() {
        List<SeckillOrderMessage> group = Arrays.asList(
                new SeckillOrderMessage(7L, 101L, 9001L),
                new SeckillOrderMessage(7L, 102L, 9002L),
                new SeckillOrderMessage(7L, 103L, 9003L));
        when(tradeOrderMapper.insertPendingBatch(anyList(), any(), anyLong()))
                .thenReturn(3);
        when(seckillVoucherMapper.decrementStock(7L, 3)).thenReturn(1);

        persister.persistGroup(7L, group);

        verify(seckillVoucherMapper).decrementStock(eq(7L), eq(3));
        verify(tradeOrderMapper).insertPendingBatch(
                eq(Arrays.asList(
                        new SeckillOrderRow(9001L, 101L),
                        new SeckillOrderRow(9002L, 102L),
                        new SeckillOrderRow(9003L, 103L))),
                eq(VOUCHER), anyLong());
    }

    @Test
    void everyPersistedOrderStillGetsItsCreateAuditRow() {
        List<SeckillOrderMessage> group = Arrays.asList(
                new SeckillOrderMessage(7L, 101L, 9001L),
                new SeckillOrderMessage(7L, 102L, 9002L));
        when(tradeOrderMapper.insertPendingBatch(anyList(), any(), anyLong()))
                .thenReturn(2);
        when(seckillVoucherMapper.decrementStock(7L, 2)).thenReturn(1);

        persister.persistGroup(7L, group);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<OrderStateLog>> captor = ArgumentCaptor.forClass(List.class);
        verify(stateLogMapper).insertCreatedBatch(captor.capture());
        assertThat(captor.getValue()).extracting(OrderStateLog::getOrderNo)
                .containsExactly(9001L, 9002L);
        assertThat(captor.getValue()).allSatisfy(row -> {
            assertThat(row.getEvent()).isEqualTo(OrderStateLog.EVENT_CREATE);
            assertThat(row.getFromStatus()).isNull();
        });
    }

    @Test
    void anIgnoredRowDegradesTheWholeGroupInsteadOfMiscountingStock() {
        List<SeckillOrderMessage> group = Arrays.asList(
                new SeckillOrderMessage(7L, 101L, 9001L),
                new SeckillOrderMessage(7L, 102L, 9002L));
        // A redelivered message: INSERT IGNORE silently skips it, so the batch count no longer
        // matches the group and a single "stock - 2" would over-decrement.
        when(tradeOrderMapper.insertPendingBatch(anyList(), any(), anyLong()))
                .thenReturn(1);

        assertThatThrownBy(() -> persister.persistGroup(7L, group))
                .isInstanceOf(BatchPersistDegradedException.class);

        verifyNoInteractions(seckillVoucherMapper);
        verify(stateLogMapper, never()).insertCreatedBatch(anyList());
    }

    @Test
    void notEnoughDbStockDegradesTheGroupSoEachOrderIsClassifiedOnItsOwn() {
        List<SeckillOrderMessage> group = Arrays.asList(
                new SeckillOrderMessage(7L, 101L, 9001L),
                new SeckillOrderMessage(7L, 102L, 9002L));
        when(tradeOrderMapper.insertPendingBatch(anyList(), any(), anyLong()))
                .thenReturn(2);
        when(seckillVoucherMapper.decrementStock(7L, 2)).thenReturn(0);

        assertThatThrownBy(() -> persister.persistGroup(7L, group))
                .isInstanceOf(BatchPersistDegradedException.class);

        verify(stateLogMapper, never()).insertCreatedBatch(anyList());
    }

    @Test
    void anEmptyGroupTouchesNothing() {
        persister.persistGroup(7L, List.of());

        verifyNoInteractions(tradeOrderMapper);
        verifyNoInteractions(seckillVoucherMapper);
        verifyNoInteractions(stateLogMapper);
    }
}
