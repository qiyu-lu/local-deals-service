package com.localdeals.trade.service;

import com.localdeals.trade.config.OrderProperties;
import com.localdeals.trade.entity.OrderStateLog;
import com.localdeals.trade.entity.OrderStatus;
import com.localdeals.trade.exception.BatchPersistDegradedException;
import com.localdeals.trade.mapper.OrderStateLogMapper;
import com.localdeals.trade.mapper.SeckillVoucherMapper;
import com.localdeals.trade.mapper.TradeOrderMapper;
import com.localdeals.trade.mq.SeckillOrderMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * Persists one voucher's slice of a consumer batch in a single transaction: one multi-row
 * {@code INSERT IGNORE}, then exactly one {@code stock - n} update for the whole slice.
 *
 * <p>Before M4 every order ran its own {@code UPDATE tb_seckill_voucher SET stock = stock - 1},
 * and 44% of the consumer's wall clock was spent queueing on that one row (M0 §4.2, D4). One
 * update per batch per voucher is what removes that queue.</p>
 *
 * <p>The fast path is all-or-nothing on purpose. Anything unusual — a redelivered message the
 * {@code INSERT IGNORE} skipped, or a stock guard that no longer holds — rolls the slice back
 * and is replayed message by message through the single-message path, which owns the
 * classification and compensation rules.</p>
 */
@Slf4j
@Service
public class SeckillOrderBatchPersister {

    private final TradeOrderMapper tradeOrderMapper;
    private final SeckillVoucherMapper seckillVoucherMapper;
    private final OrderStateLogMapper stateLogMapper;
    private final OrderProperties orderProperties;

    public SeckillOrderBatchPersister(TradeOrderMapper tradeOrderMapper,
                                      SeckillVoucherMapper seckillVoucherMapper,
                                      OrderStateLogMapper stateLogMapper,
                                      OrderProperties orderProperties) {
        this.tradeOrderMapper = tradeOrderMapper;
        this.seckillVoucherMapper = seckillVoucherMapper;
        this.stateLogMapper = stateLogMapper;
        this.orderProperties = orderProperties;
    }

    /**
     * @throws BatchPersistDegradedException when the slice must be replayed one message at a
     *                                       time; the transaction is rolled back first.
     */
    @Transactional
    public void persistGroup(long voucherId, List<SeckillOrderMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return;
        }
        List<TradeOrderMapper.SeckillOrderRow> rows = new ArrayList<>(messages.size());
        for (SeckillOrderMessage message : messages) {
            rows.add(new TradeOrderMapper.SeckillOrderRow(message.getOrderId(), message.getUserId()));
        }

        int inserted = tradeOrderMapper.insertPendingBatchFromVoucher(
                rows, voucherId, orderProperties.getPayTimeout().getSeconds());
        if (inserted != messages.size()) {
            // Either a duplicate was ignored or the voucher/shop rows are gone. Both need the
            // per-message classification; decrementing stock by the wrong n must never happen.
            throw BatchPersistDegradedException.insertSkipped(
                    "Batch insert covered " + inserted + " of " + messages.size() +
                            " orders. voucherId=" + voucherId);
        }

        if (seckillVoucherMapper.decrementStock(voucherId, messages.size()) != 1) {
            throw BatchPersistDegradedException.stockShort(
                    "DB stock cannot cover the batch. voucherId=" + voucherId +
                            " orders=" + messages.size());
        }

        List<OrderStateLog> auditRows = new ArrayList<>(messages.size());
        for (TradeOrderMapper.SeckillOrderRow row : rows) {
            auditRows.add(new OrderStateLog(row.orderNo(), null, OrderStatus.PENDING_PAY,
                    OrderStateLog.EVENT_CREATE, "SYSTEM"));
        }
        stateLogMapper.insertCreatedBatch(auditRows);
    }
}
