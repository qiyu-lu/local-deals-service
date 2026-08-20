package com.localdeals.service;

import com.localdeals.config.SeckillProperties;
import com.localdeals.dto.SeckillOrderPersistenceResult;
import com.localdeals.mq.SeckillOrderMessage;
import com.localdeals.websocket.WebSocketNotifier;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static com.localdeals.utils.RedisConstants.SECKILL_ORDER_LOCK_KEY;
import static com.localdeals.utils.RedisConstants.SECKILL_RECONCILIATION_LOCK_KEY;

/**
 * Repairs stale Redis PROCESSING reservations against the writer MySQL database.
 *
 * <p>This worker deliberately does not publish replacement MQ messages. RocketMQ owns delivery
 * retries; this worker only repairs a committed DB order or, after a hard deadline and an
 * explicit feature flag, exactly compensates a reservation which the writer DB proves absent.</p>
 */
@Slf4j
@Service
@ConditionalOnProperty(
        prefix = "local-deals.seckill.reconciliation",
        name = "enabled",
        havingValue = "true")
public class SeckillOrderReconciler {

    static final String REASON_PROCESSING_TIMEOUT = "PROCESSING_TIMEOUT";
    static final String REASON_DB_ORDER_CONFLICT = "DB_ORDER_CONFLICT";
    static final String REASON_DB_ORDER_ID_CONFLICT = "DB_ORDER_ID_CONFLICT";

    private final SeckillOrderStateService stateService;
    private final IVoucherOrderService voucherOrderService;
    private final RedissonClient redissonClient;
    private final WebSocketNotifier webSocketNotifier;
    private final SeckillProperties seckillProperties;
    private final Map<Outcome, Counter> counters = new EnumMap<>(Outcome.class);

    public SeckillOrderReconciler(SeckillOrderStateService stateService,
                                  IVoucherOrderService voucherOrderService,
                                  RedissonClient redissonClient,
                                  WebSocketNotifier webSocketNotifier,
                                  SeckillProperties seckillProperties,
                                  MeterRegistry meterRegistry) {
        this.stateService = stateService;
        this.voucherOrderService = voucherOrderService;
        this.redissonClient = redissonClient;
        this.webSocketNotifier = webSocketNotifier;
        this.seckillProperties = seckillProperties;
        for (Outcome outcome : Outcome.values()) {
            counters.put(outcome, Counter.builder("local_deals.seckill.reconciliation")
                    .tag("result", outcome.metricValue)
                    .register(meterRegistry));
        }
    }

    @Scheduled(
            initialDelayString = "#{@seckillProperties.getReconciliation().getInitialDelay().toMillis()}",
            fixedDelayString = "#{@seckillProperties.getReconciliation().getFixedDelay().toMillis()}")
    public void reconcileDueOrders() {
        SeckillProperties.Reconciliation config = seckillProperties.getReconciliation();
        if (!config.isEnabled()) {
            return;
        }

        final List<Long> dueOrderIds;
        try {
            dueOrderIds = stateService.findDueOrderIds(config.getBatchSize());
        } catch (RuntimeException e) {
            increment(Outcome.SCAN_ERROR);
            log.error("Unable to scan stale seckill reservations; this cycle is fail-closed", e);
            return;
        }
        if (dueOrderIds == null || dueOrderIds.isEmpty()) {
            return;
        }

        for (Long orderId : dueOrderIds) {
            if (orderId == null) {
                increment(Outcome.INVALID_STATE);
                continue;
            }
            try {
                reconcileOne(orderId, config);
            } catch (RuntimeException e) {
                // One corrupt or unavailable order must not prevent the rest of the bounded batch.
                increment(Outcome.STATE_ERROR);
                log.error("Failed to reconcile seckill order; leaving it retryable. orderId={}", orderId, e);
            }
        }
    }

    private void reconcileOne(Long candidateOrderId, SeckillProperties.Reconciliation config) {
        // Take the per-order scheduler lock before reading owner fields. This makes one instance
        // solely responsible for this due member until it has either claimed it or safely moved
        // its scheduler score; losing instances cannot invalidate the winner's due check.
        RLock schedulingLock = redissonClient.getLock(
                SECKILL_RECONCILIATION_LOCK_KEY + candidateOrderId);
        boolean schedulingLocked = false;
        try {
            schedulingLocked = schedulingLock.tryLock();
            if (!schedulingLocked) {
                increment(Outcome.SCHEDULER_BUSY);
                return;
            }
            reconcileScheduled(candidateOrderId, config);
        } finally {
            if (schedulingLocked && schedulingLock.isHeldByCurrentThread()) {
                schedulingLock.unlock();
            }
        }
    }

    private void reconcileScheduled(Long candidateOrderId,
                                    SeckillProperties.Reconciliation config) {
        final SeckillOrderStateService.Snapshot snapshot;
        try {
            snapshot = stateService.find(candidateOrderId);
        } catch (RuntimeException e) {
            increment(Outcome.STATE_ERROR);
            log.error("Unable to read stale seckill state; leaving it retryable. orderId={}",
                    candidateOrderId, e);
            return;
        }
        if (snapshot == null) {
            // Without a trustworthy user id there is no way to acquire the same lock as an
            // in-flight consumer. Keep the due evidence for manual repair instead of creating a
            // consumer-visible quarantine outside the shared-lock boundary.
            increment(Outcome.INVALID_STATE);
            log.error("Stale seckill state is missing or invalid; leaving it pending because " +
                    "the shared user lock cannot be resolved. orderId={}", candidateOrderId);
            try {
                if (!stateService.deferProcessingOrder(candidateOrderId)) {
                    log.warn("Unresolved seckill member disappeared before it could be deferred. orderId={}",
                            candidateOrderId);
                }
            } catch (RuntimeException e) {
                increment(Outcome.STATE_ERROR);
                log.error("Unable to defer unresolved seckill member; it remains due. orderId={}",
                        candidateOrderId, e);
            }
            return;
        }

        // The key-derived candidate id is authoritative for claim lookup. Passing the id stored
        // inside the Hash here would hide an order-id ownership mismatch instead of detecting it.
        SeckillOrderMessage message = new SeckillOrderMessage(
                snapshot.getVoucherId(), snapshot.getUserId(), candidateOrderId);
        reconcileUnderUserLock(message, config);
    }

    private void reconcileUnderUserLock(SeckillOrderMessage message,
                                        SeckillProperties.Reconciliation config) {
        RLock userLock = redissonClient.getLock(SECKILL_ORDER_LOCK_KEY + message.getUserId());
        boolean userLocked = false;
        try {
            userLocked = userLock.tryLock();
            if (!userLocked) {
                increment(Outcome.LOCK_BUSY);
                deferScheduling(message.getOrderId(), "shared user lock is busy");
                return;
            }
            reconcileClaimed(message, config);
        } finally {
            if (userLocked && userLock.isHeldByCurrentThread()) {
                userLock.unlock();
            }
        }
    }

    private void reconcileClaimed(SeckillOrderMessage message,
                                  SeckillProperties.Reconciliation config) {
        final SeckillOrderStateService.ReconciliationClaim claim;
        try {
            claim = stateService.claimForReconciliation(message);
        } catch (RuntimeException e) {
            increment(Outcome.STATE_ERROR);
            log.error("Unable to claim stale seckill reservation; leaving it retryable. orderId={}",
                    message.getOrderId(), e);
            return;
        }

        switch (claim.getDecision()) {
            case CLAIMED:
                reconcileAgainstDatabase(message, claim, config);
                return;
            case NOT_DUE:
            case TERMINAL:
            case INDEX_MISSING:
            case QUARANTINED:
                increment(Outcome.CLAIM_SKIPPED);
                return;
            case OWNERSHIP_MISMATCH:
            case STATE_INVALID:
            case RESERVATION_MISMATCH:
                quarantine(message.getOrderId(), "CLAIM_" + claim.getDecision().name());
                return;
            default:
                increment(Outcome.INVALID_STATE);
                log.error("Unknown reconciliation claim decision. orderId={} decision={}",
                        message.getOrderId(), claim.getDecision());
        }
    }

    private void reconcileAgainstDatabase(SeckillOrderMessage message,
                                          SeckillOrderStateService.ReconciliationClaim claim,
                                          SeckillProperties.Reconciliation config) {
        if (claim.getCreatedAt() == null) {
            quarantine(message.getOrderId(), "CLAIM_CREATED_AT_MISSING");
            return;
        }

        final SeckillOrderPersistenceResult persistence;
        try {
            persistence = voucherOrderService.classifyPersistence(
                    message.getOrderId(), message.getUserId(), message.getVoucherId());
        } catch (RuntimeException e) {
            increment(Outcome.DATABASE_ERROR);
            log.error("Writer DB classification failed; compensation is forbidden. orderId={}",
                    message.getOrderId(), e);
            return;
        }

        switch (persistence.getType()) {
            case EXACT_MATCH:
                repairSuccess(message);
                return;
            case ABSENT:
                handleAbsent(message, claim, config);
                return;
            case USER_VOUCHER_CONFLICT:
                handleUserVoucherConflict(message, persistence, config);
                return;
            case ORDER_ID_CONFLICT:
                handleOrderIdConflict(message, persistence);
                return;
            default:
                increment(Outcome.INVALID_STATE);
                log.error("Unknown writer DB classification. orderId={} classification={}",
                        message.getOrderId(), persistence.getType());
        }
    }

    private void repairSuccess(SeckillOrderMessage message) {
        if (!stateService.markSuccess(message)) {
            increment(Outcome.STATE_ERROR);
            log.error("Exact DB order exists but Redis could not transition to SUCCESS. orderId={}",
                    message.getOrderId());
            return;
        }
        increment(Outcome.SUCCESS_REPAIRED);
        notifyBestEffort(message, true);
    }

    private void handleAbsent(SeckillOrderMessage message,
                              SeckillOrderStateService.ReconciliationClaim claim,
                              SeckillProperties.Reconciliation config) {
        long ageSeconds = Math.max(0L, claim.getRedisNow() - claim.getCreatedAt());
        if (ageSeconds < config.getFinalTimeout().getSeconds()) {
            increment(Outcome.DEFERRED);
            return;
        }
        if (!config.isCompensationEnabled()) {
            increment(Outcome.COMPENSATION_DISABLED);
            log.warn("Stale seckill order reached the hard deadline but compensation is disabled. " +
                            "orderId={} ageSeconds={} attempts={}",
                    message.getOrderId(), ageSeconds, claim.getReconcileAttempts());
            return;
        }
        if (!stateService.compensate(message, REASON_PROCESSING_TIMEOUT)) {
            increment(Outcome.STATE_ERROR);
            log.error("Timed-out exact reservation could not be compensated. orderId={}",
                    message.getOrderId());
            return;
        }
        increment(Outcome.TIMEOUT_COMPENSATED);
        notifyBestEffort(message, false);
    }

    private void handleUserVoucherConflict(SeckillOrderMessage message,
                                           SeckillOrderPersistenceResult persistence,
                                           SeckillProperties.Reconciliation config) {
        // Suspend first. If this fail-closed guard is unavailable, no stock may be restored.
        stateService.suspendVoucher(message.getVoucherId(), REASON_DB_ORDER_CONFLICT);
        if (!config.isCompensationEnabled()) {
            increment(Outcome.PAIR_CONFLICT_BLOCKED);
            log.warn("User/voucher DB conflict found, but exact compensation is disabled. " +
                            "orderId={} persistedOrderId={}",
                    message.getOrderId(), persistence.getPersistedOrderId());
            return;
        }
        if (!stateService.compensate(message, REASON_DB_ORDER_CONFLICT)) {
            increment(Outcome.STATE_ERROR);
            log.error("Conflicting exact reservation could not be compensated. orderId={} persistedOrderId={}",
                    message.getOrderId(), persistence.getPersistedOrderId());
            return;
        }
        increment(Outcome.PAIR_CONFLICT_COMPENSATED);
        notifyBestEffort(message, false);
    }

    private void handleOrderIdConflict(SeckillOrderMessage message,
                                       SeckillOrderPersistenceResult persistence) {
        // An order-id collision invalidates the reservation proof. It is never safe to restore
        // stock automatically, even when timeout compensation is enabled.
        stateService.suspendVoucher(message.getVoucherId(), REASON_DB_ORDER_ID_CONFLICT);
        if (stateService.quarantineProcessingOrder(
                message.getOrderId(), REASON_DB_ORDER_ID_CONFLICT)) {
            increment(Outcome.ORDER_ID_QUARANTINED);
        } else {
            increment(Outcome.STATE_ERROR);
        }
        log.error("Seckill order id belongs to a different DB order and was quarantined. " +
                        "orderId={} persistedUserId={} persistedVoucherId={}",
                message.getOrderId(), persistence.getPersistedUserId(),
                persistence.getPersistedVoucherId());
    }

    private void quarantine(Long orderId, String reason) {
        try {
            if (stateService.quarantineProcessingOrder(orderId, reason)) {
                increment(Outcome.INVALID_STATE_QUARANTINED);
            } else {
                increment(Outcome.INVALID_STATE);
                log.error("Unsafe seckill state could not be quarantined. orderId={} reason={}",
                        orderId, reason);
            }
        } catch (RuntimeException e) {
            increment(Outcome.STATE_ERROR);
            log.error("Unable to quarantine unsafe seckill state. orderId={} reason={}", orderId, reason, e);
        }
    }

    private void deferScheduling(Long orderId, String reason) {
        try {
            if (!stateService.deferProcessingOrder(orderId)) {
                log.warn("Seckill member disappeared before scheduler deferral. orderId={} reason={}",
                        orderId, reason);
            }
        } catch (RuntimeException e) {
            increment(Outcome.STATE_ERROR);
            log.error("Unable to defer seckill scheduler member; it remains due. orderId={} reason={}",
                    orderId, reason, e);
        }
    }

    private void notifyBestEffort(SeckillOrderMessage message, boolean success) {
        try {
            webSocketNotifier.notify(message.getUserId(), success,
                    message.getOrderId(), message.getVoucherId());
        } catch (RuntimeException e) {
            log.warn("Seckill reconciliation notification failed. orderId={}", message.getOrderId(), e);
        }
    }

    private void increment(Outcome outcome) {
        counters.get(outcome).increment();
    }

    private enum Outcome {
        SUCCESS_REPAIRED("success_repaired"),
        TIMEOUT_COMPENSATED("timeout_compensated"),
        PAIR_CONFLICT_COMPENSATED("pair_conflict_compensated"),
        PAIR_CONFLICT_BLOCKED("pair_conflict_blocked"),
        ORDER_ID_QUARANTINED("order_id_quarantined"),
        INVALID_STATE_QUARANTINED("invalid_state_quarantined"),
        COMPENSATION_DISABLED("compensation_disabled"),
        DEFERRED("deferred"),
        CLAIM_SKIPPED("claim_skipped"),
        SCHEDULER_BUSY("scheduler_busy"),
        LOCK_BUSY("lock_busy"),
        SCAN_ERROR("scan_error"),
        DATABASE_ERROR("database_error"),
        STATE_ERROR("state_error"),
        INVALID_STATE("invalid_state");

        private final String metricValue;

        Outcome(String metricValue) {
            this.metricValue = metricValue;
        }
    }
}
