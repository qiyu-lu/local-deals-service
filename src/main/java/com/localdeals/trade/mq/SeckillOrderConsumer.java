package com.localdeals.trade.mq;

import com.localdeals.trade.exception.OrderReservationConflictException;
import com.localdeals.trade.exception.OrderIdConflictException;
import com.localdeals.trade.exception.StockExhaustedException;
import com.localdeals.trade.service.IVoucherOrderService;
import com.localdeals.trade.service.SeckillOrderStateService;
import com.localdeals.platform.observability.LocalDealsMetrics;
import com.localdeals.platform.observability.TraceContext;
import com.localdeals.platform.websocket.WebSocketNotifier;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;

/**
 * Persists one seckill order message.
 *
 * <p>Since M4 the broker is drained by {@link SeckillOrderBatchConsumer}, which persists a whole
 * batch with one INSERT and one stock update per voucher. This single-message path stays as the
 * degraded path: it owns the classification, compensation and notification rules for a message
 * the batch could not take as-is (a redelivery the INSERT IGNORE skipped, a DB conflict, or not
 * enough DB stock for the slice).</p>
 *
 * <p>Mutual exclusion with the reconciler is no longer a Redisson lock: the caller has already
 * claimed the reservation in Redis (claimOwner + lease), which is both cheaper and the same
 * mechanism the reconciler itself uses.</p>
 */
@Slf4j
@Service
public class SeckillOrderConsumer {

    @Resource
    private IVoucherOrderService voucherOrderService;

    @Resource
    private MeterRegistry meterRegistry;

    @Resource
    private WebSocketNotifier webSocketNotifier;

    @Resource
    private SeckillOrderStateService seckillOrderStateService;

    @Resource
    private LocalDealsMetrics localDealsMetrics;

    @Resource
    private OrderTimeoutScheduler orderTimeoutScheduler;

    private Counter consumeSuccessCounter;
    private Counter consumeFailureCounter;

    @PostConstruct
    private void registerMetrics() {
        consumeSuccessCounter = Counter.builder("local_deals.seckill.mq.consume")
                .tag("result", "success").register(meterRegistry);
        consumeFailureCounter = Counter.builder("local_deals.seckill.mq.consume")
                .tag("result", "failure").register(meterRegistry);
    }

    /** @throws RuntimeException for anything the broker should redeliver */
    public void onMessage(SeckillOrderMessage msg) {
        if (msg == null || msg.getUserId() == null || msg.getVoucherId() == null || msg.getOrderId() == null) {
            localDealsMetrics.recordMqConsumeOutcome(LocalDealsMetrics.MqConsumeOutcome.MALFORMED);
            consumeFailureCounter.increment();
            log.error("Rejecting malformed seckill message without touching MySQL. message={}", msg);
            throw new IllegalArgumentException("Malformed seckill message, will retry and eventually enter DLQ.");
        }
        boolean detailedOutcomeRecorded = false;
        try {
            SeckillOrderStateService.ReservationDecision decision =
                    seckillOrderStateService.validateForConsumption(msg);
            if (decision == SeckillOrderStateService.ReservationDecision.ALREADY_SUCCESS) {
                localDealsMetrics.recordMqConsumeOutcome(
                        LocalDealsMetrics.MqConsumeOutcome.ALREADY_SUCCESS);
                detailedOutcomeRecorded = true;
                consumeSuccessCounter.increment();
                log.info("Acknowledging an already successful seckill message. orderId={}", msg.getOrderId());
                return;
            }
            if (decision == SeckillOrderStateService.ReservationDecision.ALREADY_FAILED) {
                localDealsMetrics.recordMqConsumeOutcome(
                        LocalDealsMetrics.MqConsumeOutcome.ALREADY_FAILED);
                detailedOutcomeRecorded = true;
                consumeFailureCounter.increment();
                log.info("Acknowledging an already compensated seckill message. orderId={}", msg.getOrderId());
                return;
            }
            if (decision == SeckillOrderStateService.ReservationDecision.POISONED) {
                localDealsMetrics.recordMqConsumeOutcome(
                        LocalDealsMetrics.MqConsumeOutcome.RESERVATION_MISMATCH);
                detailedOutcomeRecorded = true;
                log.error("Rejecting seckill message with mismatched Redis ownership. " +
                                "voucherId={} userId={} orderId={}",
                        msg.getVoucherId(), msg.getUserId(), msg.getOrderId());
                throw new IllegalStateException(
                        "Redis reservation ownership mismatch, will retry and eventually enter DLQ. orderId=" +
                                msg.getOrderId());
            }
            if (decision != SeckillOrderStateService.ReservationDecision.PROCESS) {
                localDealsMetrics.recordMqConsumeOutcome(
                        LocalDealsMetrics.MqConsumeOutcome.STATE_MISSING);
                detailedOutcomeRecorded = true;
                throw new IllegalStateException(
                        "Redis reservation state is missing or incomplete, will retry. orderId=" + msg.getOrderId());
            }
            persistOrder(msg);
            if (!seckillOrderStateService.markSuccess(msg)) {
                throw new IllegalStateException(
                        "Exact Redis reservation could not be marked SUCCESS. orderId=" + msg.getOrderId());
            }
            consumeSuccessCounter.increment();
            localDealsMetrics.recordMqConsumeOutcome(LocalDealsMetrics.MqConsumeOutcome.PERSISTED);
            detailedOutcomeRecorded = true;
            log.debug("Seckill order persisted. orderId={}", msg.getOrderId());
            scheduleCloseBestEffort(msg);
            notifyBestEffort(msg, true);
        } catch (StockExhaustedException e) {
            handlePermanentFailure(msg, "DB_STOCK_EXHAUSTED", e);
        } catch (OrderIdConflictException e) {
            handlePermanentFailure(msg, "DB_ORDER_ID_CONFLICT", e);
        } catch (OrderReservationConflictException e) {
            handlePermanentFailure(msg, "DB_ORDER_CONFLICT", e);
        } catch (Exception e) {
            // Transient failures (network, DB timeout, etc.) — let RocketMQ retry.
            if (!detailedOutcomeRecorded) {
                localDealsMetrics.recordMqConsumeOutcome(
                        LocalDealsMetrics.MqConsumeOutcome.TRANSIENT_ERROR);
            }
            consumeFailureCounter.increment();
            log.error("Transient failure processing seckill order, will retry. orderId={}", msg.getOrderId(), e);
            throw e;
        }
    }

    private void persistOrder(SeckillOrderMessage msg) {
        long startedAt = System.nanoTime();
        LocalDealsMetrics.SeckillDbPersistResult result =
                LocalDealsMetrics.SeckillDbPersistResult.FAILURE;
        try {
            voucherOrderService.createPendingOrder(msg);
            result = LocalDealsMetrics.SeckillDbPersistResult.SUCCESS;
        } finally {
            localDealsMetrics.recordSeckillDbPersist(result, System.nanoTime() - startedAt);
        }
    }

    private void handlePermanentFailure(SeckillOrderMessage msg, String reason, RuntimeException cause) {
        try {
            // Stop new admissions before restoring Redis stock: DB and Redis have diverged and
            // require reconciliation. Compensation itself is exact and idempotent.
            seckillOrderStateService.suspendVoucher(msg.getVoucherId(), reason);
            if (!seckillOrderStateService.compensate(msg, reason)) {
                throw new IllegalStateException(
                        "Redis reservation compensation did not match. orderId=" + msg.getOrderId(), cause);
            }
        } catch (RuntimeException compensationFailure) {
            localDealsMetrics.recordMqConsumeOutcome(
                    LocalDealsMetrics.MqConsumeOutcome.COMPENSATION_ERROR);
            consumeFailureCounter.increment();
            log.error("Permanent DB failure could not be compensated; MQ will retry. orderId={}",
                    msg.getOrderId(), compensationFailure);
            throw compensationFailure;
        }

        localDealsMetrics.recordMqConsumeOutcome(LocalDealsMetrics.MqConsumeOutcome.COMPENSATED);
        consumeFailureCounter.increment();
        log.warn("Permanent seckill failure compensated. voucherId={} orderId={} reason={}",
                msg.getVoucherId(), msg.getOrderId(), reason);
        notifyBestEffort(msg, false);
    }

    private void scheduleCloseBestEffort(SeckillOrderMessage msg) {
        try {
            orderTimeoutScheduler.scheduleClose(msg.getOrderId(),
                    TraceContext.accept(msg.getTraceId()));
        } catch (RuntimeException e) {
            // The order is committed; OrderTimeoutScanner closes it if no timer message exists.
            log.warn("Order timeout scheduling failed. orderId={}", msg.getOrderId(), e);
        }
    }

    private void notifyBestEffort(SeckillOrderMessage msg, boolean success) {
        try {
            webSocketNotifier.notify(msg.getUserId(), success, msg.getOrderId(), msg.getVoucherId());
        } catch (RuntimeException e) {
            // The durable status endpoint is authoritative; a pub/sub notification must never
            // cause an already-finalized DB/Redis transition to be redelivered.
            log.warn("Seckill WebSocket notification failed. orderId={}", msg.getOrderId(), e);
        }
    }
}
