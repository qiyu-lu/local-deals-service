package com.localdeals.trade.mq;

import com.localdeals.platform.observability.LocalDealsMetrics;
import com.localdeals.platform.websocket.WebSocketNotifier;
import com.localdeals.trade.exception.BatchPersistDegradedException;
import com.localdeals.trade.service.SeckillOrderBatchPersister;
import com.localdeals.trade.service.SeckillOrderStateService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Drains one consume batch: one Redis round trip to classify and claim it, one INSERT plus one
 * stock update per voucher, one Redis round trip to finalize it.
 *
 * <p>Anything the fast path cannot take as-is falls back to the single-message
 * {@link SeckillOrderConsumer}, which keeps the M2/M3 classification and compensation rules.</p>
 */
@Slf4j
@Service
public class SeckillOrderBatchProcessor {

    private final SeckillOrderStateService stateService;
    private final SeckillOrderBatchPersister batchPersister;
    private final SeckillOrderConsumer singleMessageConsumer;
    private final OrderTimeoutScheduler orderTimeoutScheduler;
    private final WebSocketNotifier webSocketNotifier;
    private final LocalDealsMetrics localDealsMetrics;

    public SeckillOrderBatchProcessor(SeckillOrderStateService stateService,
                                      SeckillOrderBatchPersister batchPersister,
                                      SeckillOrderConsumer singleMessageConsumer,
                                      OrderTimeoutScheduler orderTimeoutScheduler,
                                      WebSocketNotifier webSocketNotifier,
                                      LocalDealsMetrics localDealsMetrics) {
        this.stateService = stateService;
        this.batchPersister = batchPersister;
        this.singleMessageConsumer = singleMessageConsumer;
        this.orderTimeoutScheduler = orderTimeoutScheduler;
        this.webSocketNotifier = webSocketNotifier;
        this.localDealsMetrics = localDealsMetrics;
    }

    /**
     * @return true when every message of the batch is finished and the batch can be acknowledged;
     * false when at least one message must be redelivered. Redelivery is always safe: the claim,
     * the INSERT IGNORE and the SUCCESS transition are all idempotent.
     */
    public boolean process(List<SeckillOrderMessage> batch) {
        if (batch == null || batch.isEmpty()) {
            return true;
        }
        List<SeckillOrderMessage> wellFormed = new ArrayList<>(batch.size());
        boolean retryNeeded = false;
        for (SeckillOrderMessage message : batch) {
            if (message == null || message.getUserId() == null || message.getVoucherId() == null
                    || message.getOrderId() == null) {
                // Nothing can be done with it; a retry only sends it to the DLQ later.
                localDealsMetrics.recordMqConsumeOutcome(LocalDealsMetrics.MqConsumeOutcome.MALFORMED);
                log.error("Dropping malformed seckill message without touching MySQL. message={}", message);
                continue;
            }
            wellFormed.add(message);
        }
        if (wellFormed.isEmpty()) {
            return true;
        }

        final List<SeckillOrderStateService.PersistClaim> claims;
        try {
            claims = stateService.claimForPersistence(wellFormed);
        } catch (RuntimeException e) {
            localDealsMetrics.recordMqConsumeOutcome(LocalDealsMetrics.MqConsumeOutcome.TRANSIENT_ERROR);
            log.error("Unable to claim a seckill batch; it will be redelivered. size={}",
                    wellFormed.size(), e);
            return false;
        }

        List<SeckillOrderMessage> claimed = new ArrayList<>(wellFormed.size());
        for (int i = 0; i < wellFormed.size(); i++) {
            SeckillOrderMessage message = wellFormed.get(i);
            switch (claims.get(i)) {
                case CLAIMED:
                    claimed.add(message);
                    break;
                case ALREADY_SUCCESS:
                    localDealsMetrics.recordMqConsumeOutcome(
                            LocalDealsMetrics.MqConsumeOutcome.ALREADY_SUCCESS);
                    break;
                case ALREADY_FAILED:
                    localDealsMetrics.recordMqConsumeOutcome(
                            LocalDealsMetrics.MqConsumeOutcome.ALREADY_FAILED);
                    break;
                case CLAIM_BUSY:
                    // The reconciler (or another consumer) holds the lease; come back later.
                    localDealsMetrics.recordMqConsumeOutcome(
                            LocalDealsMetrics.MqConsumeOutcome.CLAIM_BUSY);
                    retryNeeded = true;
                    break;
                case POISONED:
                    localDealsMetrics.recordMqConsumeOutcome(
                            LocalDealsMetrics.MqConsumeOutcome.RESERVATION_MISMATCH);
                    log.error("Seckill message does not own its reservation. orderId={}",
                            message.getOrderId());
                    retryNeeded = true;
                    break;
                default:
                    localDealsMetrics.recordMqConsumeOutcome(
                            LocalDealsMetrics.MqConsumeOutcome.STATE_MISSING);
                    retryNeeded = true;
                    break;
            }
        }
        if (claimed.isEmpty()) {
            return !retryNeeded;
        }

        List<SeckillOrderMessage> persisted = new ArrayList<>(claimed.size());
        for (Map.Entry<Long, List<SeckillOrderMessage>> group : groupByVoucher(claimed).entrySet()) {
            retryNeeded |= !persistGroup(group.getKey(), group.getValue(), persisted);
        }
        if (persisted.isEmpty()) {
            return !retryNeeded;
        }

        return finalizeBatch(persisted) && !retryNeeded;
    }

    /** @return false when at least one order of the group must be redelivered */
    private boolean persistGroup(Long voucherId, List<SeckillOrderMessage> group,
                                 List<SeckillOrderMessage> persisted) {
        long startedAt = System.nanoTime();
        try {
            batchPersister.persistGroup(voucherId, group);
            persisted.addAll(group);
            localDealsMetrics.recordSeckillDbPersist(
                    LocalDealsMetrics.SeckillDbPersistResult.SUCCESS, System.nanoTime() - startedAt);
            return true;
        } catch (BatchPersistDegradedException degraded) {
            localDealsMetrics.recordSeckillDbPersist(
                    LocalDealsMetrics.SeckillDbPersistResult.FAILURE, System.nanoTime() - startedAt);
            log.info("Seckill batch degraded to single-message handling. voucherId={} size={} reason={}",
                    voucherId, group.size(), degraded.getMessage());
            return persistOneByOne(group, persisted);
        } catch (RuntimeException e) {
            localDealsMetrics.recordSeckillDbPersist(
                    LocalDealsMetrics.SeckillDbPersistResult.FAILURE, System.nanoTime() - startedAt);
            localDealsMetrics.recordMqConsumeOutcome(LocalDealsMetrics.MqConsumeOutcome.TRANSIENT_ERROR);
            log.error("Seckill batch persistence failed; it will be redelivered. voucherId={} size={}",
                    voucherId, group.size(), e);
            return false;
        }
    }

    private boolean persistOneByOne(List<SeckillOrderMessage> group,
                                    List<SeckillOrderMessage> persisted) {
        boolean complete = true;
        for (SeckillOrderMessage message : group) {
            try {
                // The single-message path finalizes Redis, schedules the close and notifies on
                // its own, so a message it handled is already finished here.
                singleMessageConsumer.onMessage(message);
            } catch (RuntimeException e) {
                complete = false;
            }
        }
        return complete;
    }

    private boolean finalizeBatch(List<SeckillOrderMessage> persisted) {
        final List<Boolean> finalized;
        try {
            finalized = stateService.markSuccessBatch(persisted);
        } catch (RuntimeException e) {
            // The orders are committed; a redelivery repairs Redis and acknowledges.
            localDealsMetrics.recordMqConsumeOutcome(LocalDealsMetrics.MqConsumeOutcome.TRANSIENT_ERROR);
            log.error("Committed seckill batch could not be marked SUCCESS; it will be redelivered. size={}",
                    persisted.size(), e);
            return false;
        }

        boolean complete = true;
        for (int i = 0; i < persisted.size(); i++) {
            SeckillOrderMessage message = persisted.get(i);
            if (!Boolean.TRUE.equals(finalized.get(i))) {
                complete = false;
                log.error("Exact Redis reservation could not be marked SUCCESS. orderId={}",
                        message.getOrderId());
                continue;
            }
            localDealsMetrics.recordMqConsumeOutcome(LocalDealsMetrics.MqConsumeOutcome.PERSISTED);
            scheduleCloseBestEffort(message);
            notifyBestEffort(message);
        }
        return complete;
    }

    private static Map<Long, List<SeckillOrderMessage>> groupByVoucher(List<SeckillOrderMessage> batch) {
        Map<Long, List<SeckillOrderMessage>> groups = new LinkedHashMap<>();
        for (SeckillOrderMessage message : batch) {
            groups.computeIfAbsent(message.getVoucherId(), id -> new ArrayList<>()).add(message);
        }
        return groups;
    }

    private void scheduleCloseBestEffort(SeckillOrderMessage message) {
        try {
            orderTimeoutScheduler.scheduleClose(message.getOrderId());
        } catch (RuntimeException e) {
            // The order is committed; OrderTimeoutScanner closes it if no timer message exists.
            log.warn("Order timeout scheduling failed. orderId={}", message.getOrderId(), e);
        }
    }

    private void notifyBestEffort(SeckillOrderMessage message) {
        try {
            webSocketNotifier.notify(message.getUserId(), true, message.getOrderId(),
                    message.getVoucherId());
        } catch (RuntimeException e) {
            log.warn("Seckill WebSocket notification failed. orderId={}", message.getOrderId(), e);
        }
    }
}
