package com.localdeals.mq;

import com.localdeals.exception.OrderReservationConflictException;
import com.localdeals.exception.OrderIdConflictException;
import com.localdeals.exception.StockExhaustedException;
import com.localdeals.service.IVoucherOrderService;
import com.localdeals.service.SeckillOrderStateService;
import com.localdeals.observability.LocalDealsMetrics;
import com.localdeals.websocket.WebSocketNotifier;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.apache.rocketmq.common.consumer.ConsumeFromWhere;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.apache.rocketmq.spring.core.RocketMQPushConsumerLifecycleListener;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;

import static com.localdeals.utils.RedisConstants.SECKILL_ORDER_LOCK_KEY;

/**
 * Consumes seckill order messages produced after a successful Redis Lua admission check and
 * persists them to MySQL.
 */
@Slf4j
@Service
@RocketMQMessageListener(topic = "seckill-order-topic", consumerGroup = "seckill-consumer-group")
public class SeckillOrderConsumer implements RocketMQListener<SeckillOrderMessage>,
        RocketMQPushConsumerLifecycleListener {

    @Resource
    private IVoucherOrderService voucherOrderService;

    @Resource
    private RedissonClient redissonClient;

    @Resource
    private MeterRegistry meterRegistry;

    @Resource
    private WebSocketNotifier webSocketNotifier;

    @Resource
    private SeckillOrderStateService seckillOrderStateService;

    @Resource
    private LocalDealsMetrics localDealsMetrics;

    private Counter consumeSuccessCounter;
    private Counter consumeFailureCounter;

    @Override
    public void prepareStart(DefaultMQPushConsumer consumer) {
        // A brand-new deployment may start before the topic is provisioned. If orders are
        // committed while the consumer has no offset yet, RocketMQ's LAST_OFFSET default can
        // skip those first messages when the topic later becomes visible. Existing offsets are
        // still authoritative; this only changes the no-offset bootstrap position.
        consumer.setConsumeFromWhere(ConsumeFromWhere.CONSUME_FROM_FIRST_OFFSET);
    }

    @PostConstruct
    private void registerMetrics() {
        consumeSuccessCounter = Counter.builder("local_deals.seckill.mq.consume")
                .tag("result", "success").register(meterRegistry);
        consumeFailureCounter = Counter.builder("local_deals.seckill.mq.consume")
                .tag("result", "failure").register(meterRegistry);
    }

    @Override
    public void onMessage(SeckillOrderMessage msg) {
        if (msg == null || msg.getUserId() == null || msg.getVoucherId() == null || msg.getOrderId() == null) {
            localDealsMetrics.recordMqConsumeOutcome(LocalDealsMetrics.MqConsumeOutcome.MALFORMED);
            consumeFailureCounter.increment();
            log.error("Rejecting malformed seckill message without touching MySQL. message={}", msg);
            throw new IllegalArgumentException("Malformed seckill message, will retry and eventually enter DLQ.");
        }
        final RLock lock;
        final boolean locked;
        try {
            lock = redissonClient.getLock(SECKILL_ORDER_LOCK_KEY + msg.getUserId());
            locked = lock.tryLock();
        } catch (RuntimeException lockFailure) {
            localDealsMetrics.recordMqConsumeOutcome(
                    LocalDealsMetrics.MqConsumeOutcome.TRANSIENT_ERROR);
            throw lockFailure;
        }
        if (!locked) {
            localDealsMetrics.recordMqConsumeOutcome(LocalDealsMetrics.MqConsumeOutcome.LOCK_BUSY);
            log.warn("Seckill order lock busy. userId={}, orderId={}", msg.getUserId(), msg.getOrderId());
            throw new IllegalStateException("Order lock busy, will retry.");
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
            voucherOrderService.createVoucherOrder(msg.toVoucherOrder());
            if (!seckillOrderStateService.markSuccess(msg)) {
                throw new IllegalStateException(
                        "Exact Redis reservation could not be marked SUCCESS. orderId=" + msg.getOrderId());
            }
            consumeSuccessCounter.increment();
            localDealsMetrics.recordMqConsumeOutcome(LocalDealsMetrics.MqConsumeOutcome.PERSISTED);
            detailedOutcomeRecorded = true;
            log.debug("Seckill order persisted. orderId={}", msg.getOrderId());
            notifyBestEffort(msg, true);
        } catch (StockExhaustedException e) {
            handlePermanentFailure(msg, "DB_STOCK_EXHAUSTED", e);
        } catch (OrderIdConflictException e) {
            handleOrderIdConflict(msg, e);
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
        } finally {
            lock.unlock();
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

    private void handleOrderIdConflict(SeckillOrderMessage msg, OrderIdConflictException cause) {
        try {
            seckillOrderStateService.suspendVoucher(msg.getVoucherId(), "DB_ORDER_ID_CONFLICT");
            if (!seckillOrderStateService.quarantineProcessingOrder(
                    msg.getOrderId(), "DB_ORDER_ID_CONFLICT")) {
                throw new IllegalStateException(
                        "Order-id conflict could not be quarantined. orderId=" + msg.getOrderId(), cause);
            }
        } catch (RuntimeException quarantineFailure) {
            localDealsMetrics.recordMqConsumeOutcome(
                    LocalDealsMetrics.MqConsumeOutcome.QUARANTINE_ERROR);
            consumeFailureCounter.increment();
            log.error("Order-id conflict could not be isolated; MQ will retry. orderId={}",
                    msg.getOrderId(), quarantineFailure);
            throw quarantineFailure;
        }

        localDealsMetrics.recordMqConsumeOutcome(LocalDealsMetrics.MqConsumeOutcome.QUARANTINED);
        consumeFailureCounter.increment();
        log.error("Order-id conflict was quarantined without Redis compensation; MQ will retain " +
                        "the poison message through retry/DLQ. voucherId={} orderId={}",
                msg.getVoucherId(), msg.getOrderId(), cause);
        throw cause;
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
