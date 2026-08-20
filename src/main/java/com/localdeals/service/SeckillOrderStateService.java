package com.localdeals.service;

import com.localdeals.config.SeckillProperties;
import com.localdeals.mq.SeckillOrderMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.localdeals.utils.RedisConstants.SECKILL_META_KEY;
import static com.localdeals.utils.RedisConstants.SECKILL_ORDER_KEY;
import static com.localdeals.utils.RedisConstants.SECKILL_ORDER_STATUS_KEY;
import static com.localdeals.utils.RedisConstants.SECKILL_ORDER_STATUS_TTL_SECONDS;
import static com.localdeals.utils.RedisConstants.SECKILL_PROCESSING_INDEX_KEY;
import static com.localdeals.utils.RedisConstants.SECKILL_PROCESSING_QUARANTINE_KEY;
import static com.localdeals.utils.RedisConstants.SECKILL_PROCESSING_QUARANTINE_REASON_KEY;
import static com.localdeals.utils.RedisConstants.SECKILL_RESERVATION_KEY;
import static com.localdeals.utils.RedisConstants.SECKILL_STOCK_KEY;

/** Owns the Redis state transitions which follow seckill admission. */
@Slf4j
@Service
public class SeckillOrderStateService {

    public static final String STATUS_PROCESSING = "PROCESSING";
    public static final String STATUS_SUCCESS = "SUCCESS";
    public static final String STATUS_FAILED = "FAILED";

    private static final DefaultRedisScript<Long> MARK_SUCCESS_SCRIPT;
    private static final DefaultRedisScript<Long> COMPENSATE_SCRIPT;
    private static final DefaultRedisScript<Long> VALIDATE_RESERVATION_SCRIPT;
    private static final DefaultRedisScript<List> RECONCILE_DUE_SCRIPT;
    private static final DefaultRedisScript<List> RECONCILE_CLAIM_SCRIPT;
    private static final DefaultRedisScript<Long> RECONCILE_QUARANTINE_SCRIPT;
    private static final DefaultRedisScript<Long> RECONCILE_BACKFILL_SCRIPT;
    private static final DefaultRedisScript<Long> RECONCILE_DEFER_UNRESOLVED_SCRIPT;

    static {
        MARK_SUCCESS_SCRIPT = script("lua/seckill_mark_success.lua");
        COMPENSATE_SCRIPT = script("lua/seckill_compensate.lua");
        VALIDATE_RESERVATION_SCRIPT = script("lua/seckill_validate_reservation.lua");
        RECONCILE_DUE_SCRIPT = listScript("lua/seckill_reconcile_due.lua");
        RECONCILE_CLAIM_SCRIPT = listScript("lua/seckill_reconcile_claim.lua");
        RECONCILE_QUARANTINE_SCRIPT = script("lua/seckill_reconcile_quarantine.lua");
        RECONCILE_BACKFILL_SCRIPT = script("lua/seckill_reconcile_backfill.lua");
        RECONCILE_DEFER_UNRESOLVED_SCRIPT = script("lua/seckill_reconcile_defer_unresolved.lua");
    }

    private final StringRedisTemplate stringRedisTemplate;
    private final SeckillProperties seckillProperties;

    public SeckillOrderStateService(StringRedisTemplate stringRedisTemplate,
                                    SeckillProperties seckillProperties) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.seckillProperties = seckillProperties;
    }

    /**
     * Atomically classifies a broker message before any MySQL mutation. Only an exact
     * PROCESSING status plus its exact user-to-order reservation is eligible for persistence.
     */
    public ReservationDecision validateForConsumption(SeckillOrderMessage message) {
        Long result = stringRedisTemplate.execute(
                VALIDATE_RESERVATION_SCRIPT,
                Arrays.asList(
                        orderStatusKey(message.getOrderId()),
                        reservationKey(message.getVoucherId()),
                        SECKILL_PROCESSING_QUARANTINE_KEY),
                message.getUserId().toString(),
                message.getVoucherId().toString(),
                message.getOrderId().toString()
        );
        if (Long.valueOf(1L).equals(result)) {
            return ReservationDecision.PROCESS;
        }
        if (Long.valueOf(2L).equals(result)) {
            return ReservationDecision.ALREADY_SUCCESS;
        }
        if (Long.valueOf(3L).equals(result)) {
            return ReservationDecision.ALREADY_FAILED;
        }
        if (Long.valueOf(4L).equals(result)) {
            return ReservationDecision.POISONED;
        }
        if (Long.valueOf(0L).equals(result)) {
            return ReservationDecision.RETRYABLE_STATE_MISSING;
        }
        throw new IllegalStateException("Unexpected Redis reservation validation result: " + result);
    }

    public boolean markSuccess(SeckillOrderMessage message) {
        Long result = stringRedisTemplate.execute(
                MARK_SUCCESS_SCRIPT,
                Arrays.asList(
                        orderStatusKey(message.getOrderId()),
                        reservationKey(message.getVoucherId()),
                        SECKILL_PROCESSING_INDEX_KEY),
                message.getUserId().toString(),
                message.getVoucherId().toString(),
                message.getOrderId().toString(),
                SECKILL_ORDER_STATUS_TTL_SECONDS.toString()
        );
        return Long.valueOf(1L).equals(result);
    }

    /**
     * Releases stock at most once. A repeated call after a completed compensation is treated
     * as success so an MQ redelivery can be acknowledged safely.
     */
    public boolean compensate(SeckillOrderMessage message, String reason) {
        Long result = stringRedisTemplate.execute(
                COMPENSATE_SCRIPT,
                Arrays.asList(
                        SECKILL_STOCK_KEY + message.getVoucherId(),
                        SECKILL_ORDER_KEY + message.getVoucherId(),
                        reservationKey(message.getVoucherId()),
                        orderStatusKey(message.getOrderId()),
                        SECKILL_PROCESSING_INDEX_KEY,
                        SECKILL_PROCESSING_QUARANTINE_KEY),
                message.getUserId().toString(),
                message.getVoucherId().toString(),
                message.getOrderId().toString(),
                reason,
                SECKILL_ORDER_STATUS_TTL_SECONDS.toString()
        );
        if (Long.valueOf(1L).equals(result) || Long.valueOf(2L).equals(result)) {
            return true;
        }
        Snapshot snapshot = find(message.getOrderId());
        return snapshot != null && snapshot.belongsTo(message) && STATUS_FAILED.equals(snapshot.getStatus());
    }

    /**
     * Returns at most the configured batch size of due order ids without removing them.
     * Malformed raw members are atomically moved to quarantine so one bad member cannot make
     * every reconciliation round fail at the Java long conversion boundary.
     */
    public List<Long> findDueOrderIds(int limit) {
        if (limit <= 0) {
            throw new IllegalArgumentException("reconciliation due-query limit must be positive");
        }
        int boundedLimit = Math.min(limit, seckillProperties.getReconciliation().getBatchSize());
        List<?> rawMembers = stringRedisTemplate.execute(
                RECONCILE_DUE_SCRIPT,
                Collections.singletonList(SECKILL_PROCESSING_INDEX_KEY),
                Integer.toString(boundedLimit));
        if (rawMembers == null || rawMembers.isEmpty()) {
            return Collections.emptyList();
        }

        List<Long> orderIds = new ArrayList<>(rawMembers.size());
        for (Object rawMember : rawMembers) {
            String member = redisString(rawMember);
            Long orderId = parseCanonicalPositiveLong(member);
            if (orderId != null) {
                orderIds.add(orderId);
            } else {
                try {
                    quarantineProcessingMember(member, "INVALID_PROCESSING_INDEX_MEMBER");
                } catch (RuntimeException quarantineFailure) {
                    log.error("Unable to quarantine malformed PROCESSING index member. member={}",
                            member, quarantineFailure);
                }
            }
        }
        return orderIds;
    }

    /** Claims a due exact reservation and moves its due score forward by retryDelay. */
    public ReconciliationClaim claimForReconciliation(SeckillOrderMessage message) {
        requireCompleteMessage(message);
        List<?> response = stringRedisTemplate.execute(
                RECONCILE_CLAIM_SCRIPT,
                Arrays.asList(
                        orderStatusKey(message.getOrderId()),
                        reservationKey(message.getVoucherId()),
                        SECKILL_PROCESSING_INDEX_KEY,
                        SECKILL_PROCESSING_QUARANTINE_KEY),
                message.getUserId().toString(),
                message.getVoucherId().toString(),
                message.getOrderId().toString(),
                Long.toString(seckillProperties.getReconciliation().getRetryDelay().getSeconds()));
        if (response == null || response.size() != 4) {
            throw new IllegalStateException("Unexpected Redis reconciliation claim response: " + response);
        }

        int code = requiredInt(response.get(0), "decision");
        long redisNow = requiredLong(response.get(1), "redisNow");
        Long createdAt = optionalLong(response.get(2));
        long attempts = requiredLong(response.get(3), "reconcileAttempts");
        return new ReconciliationClaim(claimDecision(code), redisNow, createdAt, attempts);
    }

    /**
     * Moves an unresolved canonical member out of the head of the bounded due batch without
     * changing its business status. The Lua also removes a concurrently terminal/quarantined
     * member, so it cannot recreate an index entry after markSuccess or quarantine wins a race.
     */
    public boolean deferProcessingOrder(Long orderId) {
        if (orderId == null || orderId <= 0L) {
            throw new IllegalArgumentException("orderId must be positive");
        }
        Long result = stringRedisTemplate.execute(
                RECONCILE_DEFER_UNRESOLVED_SCRIPT,
                Arrays.asList(
                        orderStatusKey(orderId),
                        SECKILL_PROCESSING_INDEX_KEY,
                        SECKILL_PROCESSING_QUARANTINE_KEY),
                orderId.toString(),
                Long.toString(seckillProperties.getReconciliation().getRetryDelay().getSeconds()));
        return Long.valueOf(1L).equals(result) || Long.valueOf(2L).equals(result) ||
                Long.valueOf(3L).equals(result);
    }

    /** Convenience overload for a valid numeric order id. */
    public boolean quarantineProcessingOrder(Long orderId, String reason) {
        if (orderId == null) {
            throw new IllegalArgumentException("orderId must not be null");
        }
        return quarantineProcessingMember(orderId.toString(), reason);
    }

    /**
     * Atomically moves an exact raw ZSET member to a timestamped quarantine. This raw form is
     * also used for malformed members which cannot safely be represented as a Java Long.
     */
    public boolean quarantineProcessingMember(String orderIdMember, String reason) {
        if (orderIdMember == null) {
            throw new IllegalArgumentException("orderIdMember must not be null");
        }
        String safeReason = reason == null || reason.trim().isEmpty()
                ? "UNSPECIFIED_RECONCILIATION_QUARANTINE"
                : reason.trim();
        Long result = stringRedisTemplate.execute(
                RECONCILE_QUARANTINE_SCRIPT,
                Arrays.asList(
                        SECKILL_PROCESSING_INDEX_KEY,
                        SECKILL_PROCESSING_QUARANTINE_KEY,
                        SECKILL_PROCESSING_QUARANTINE_REASON_KEY),
                orderIdMember,
                safeReason);
        return Long.valueOf(1L).equals(result) || Long.valueOf(2L).equals(result);
    }

    /**
     * Adds an old exact PROCESSING reservation to the durable due index without overwriting
     * an existing live score. Exact old status hashes are persisted atomically with the add.
     */
    public ProcessingBackfillDecision backfillProcessingOrder(SeckillOrderMessage message) {
        requireCompleteMessage(message);
        Long result = stringRedisTemplate.execute(
                RECONCILE_BACKFILL_SCRIPT,
                Arrays.asList(
                        orderStatusKey(message.getOrderId()),
                        reservationKey(message.getVoucherId()),
                        SECKILL_PROCESSING_INDEX_KEY,
                        SECKILL_PROCESSING_QUARANTINE_KEY),
                message.getUserId().toString(),
                message.getVoucherId().toString(),
                message.getOrderId().toString(),
                Long.toString(seckillProperties.getReconciliation().getStaleAfter().getSeconds()));
        if (Long.valueOf(1L).equals(result)) {
            return ProcessingBackfillDecision.INDEXED;
        }
        if (Long.valueOf(2L).equals(result)) {
            return ProcessingBackfillDecision.ALREADY_INDEXED;
        }
        if (Long.valueOf(3L).equals(result)) {
            return ProcessingBackfillDecision.TERMINAL;
        }
        if (Long.valueOf(4L).equals(result)) {
            return ProcessingBackfillDecision.OWNERSHIP_MISMATCH;
        }
        if (Long.valueOf(5L).equals(result)) {
            return ProcessingBackfillDecision.STATE_INVALID;
        }
        if (Long.valueOf(6L).equals(result)) {
            return ProcessingBackfillDecision.RESERVATION_MISMATCH;
        }
        if (Long.valueOf(7L).equals(result)) {
            return ProcessingBackfillDecision.QUARANTINED;
        }
        throw new IllegalStateException("Unexpected Redis PROCESSING backfill result: " + result);
    }

    /** Fail closed when Redis and DB stock disagree until an operator reconciles the voucher. */
    public void suspendVoucher(Long voucherId, String reason) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("status", "SUSPENDED");
        values.put("suspendReason", reason);
        values.put("updatedAt", Long.toString(Instant.now().getEpochSecond()));
        stringRedisTemplate.opsForHash().putAll(SECKILL_META_KEY + voucherId, values);
    }

    public Snapshot find(Long orderId) {
        Map<Object, Object> values = stringRedisTemplate.opsForHash().entries(orderStatusKey(orderId));
        if (values == null || values.isEmpty()) {
            return null;
        }
        Long storedOrderId = parseLong(values.get("orderId"));
        Long userId = parseLong(values.get("userId"));
        Long voucherId = parseLong(values.get("voucherId"));
        String status = stringValue(values.get("status"));
        if (storedOrderId == null || userId == null || voucherId == null || status == null) {
            return null;
        }
        String reason = stringValue(values.get("reason"));
        return new Snapshot(storedOrderId, userId, voucherId, status,
                reason == null || reason.trim().isEmpty() ? null : reason);
    }

    private static String reservationKey(Long voucherId) {
        return SECKILL_RESERVATION_KEY + voucherId;
    }

    private static String orderStatusKey(Long orderId) {
        return SECKILL_ORDER_STATUS_KEY + orderId;
    }

    private static DefaultRedisScript<Long> script(String location) {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource(location));
        script.setResultType(Long.class);
        return script;
    }

    @SuppressWarnings("rawtypes")
    private static DefaultRedisScript<List> listScript(String location) {
        DefaultRedisScript<List> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource(location));
        script.setResultType(List.class);
        return script;
    }

    private static void requireCompleteMessage(SeckillOrderMessage message) {
        if (message == null || message.getOrderId() == null || message.getUserId() == null ||
                message.getVoucherId() == null) {
            throw new IllegalArgumentException("seckill order message ownership must be complete");
        }
    }

    private static ReconciliationClaimDecision claimDecision(int code) {
        switch (code) {
            case 1:
                return ReconciliationClaimDecision.CLAIMED;
            case 2:
                return ReconciliationClaimDecision.NOT_DUE;
            case 3:
                return ReconciliationClaimDecision.TERMINAL;
            case 4:
                return ReconciliationClaimDecision.OWNERSHIP_MISMATCH;
            case 5:
                return ReconciliationClaimDecision.STATE_INVALID;
            case 6:
                return ReconciliationClaimDecision.RESERVATION_MISMATCH;
            case 7:
                return ReconciliationClaimDecision.INDEX_MISSING;
            case 8:
                return ReconciliationClaimDecision.QUARANTINED;
            default:
                throw new IllegalStateException("Unexpected reconciliation claim decision: " + code);
        }
    }

    private static int requiredInt(Object value, String field) {
        long parsed = requiredLong(value, field);
        if (parsed < Integer.MIN_VALUE || parsed > Integer.MAX_VALUE) {
            throw new IllegalStateException("Redis reconciliation " + field + " is out of range: " + parsed);
        }
        return (int) parsed;
    }

    private static long requiredLong(Object value, String field) {
        Long parsed = optionalLong(value);
        if (parsed == null) {
            throw new IllegalStateException("Redis reconciliation response has invalid " + field + ": " + value);
        }
        return parsed;
    }

    private static Long optionalLong(Object value) {
        String string = redisString(value);
        if (string.isEmpty()) {
            return null;
        }
        try {
            return Long.valueOf(string);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String redisString(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof byte[]) {
            return new String((byte[]) value, StandardCharsets.UTF_8);
        }
        return value.toString();
    }

    private static Long parseLong(Object value) {
        try {
            return value == null ? null : Long.valueOf(value.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Long parseCanonicalPositiveLong(String value) {
        if (value == null || value.isEmpty() || value.charAt(0) == '0') {
            return null;
        }
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (current < '0' || current > '9') {
                return null;
            }
        }
        try {
            long parsed = Long.parseLong(value);
            return parsed > 0L ? parsed : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static String stringValue(Object value) {
        return value == null ? null : value.toString();
    }

    public static final class Snapshot {
        private final Long orderId;
        private final Long userId;
        private final Long voucherId;
        private final String status;
        private final String reason;

        public Snapshot(Long orderId, Long userId, Long voucherId, String status, String reason) {
            this.orderId = orderId;
            this.userId = userId;
            this.voucherId = voucherId;
            this.status = status;
            this.reason = reason;
        }

        public boolean belongsTo(SeckillOrderMessage message) {
            return orderId.equals(message.getOrderId()) && userId.equals(message.getUserId()) &&
                    voucherId.equals(message.getVoucherId());
        }

        public Long getOrderId() {
            return orderId;
        }

        public Long getUserId() {
            return userId;
        }

        public Long getVoucherId() {
            return voucherId;
        }

        public String getStatus() {
            return status;
        }

        public String getReason() {
            return reason;
        }
    }

    public enum ReservationDecision {
        PROCESS,
        ALREADY_SUCCESS,
        ALREADY_FAILED,
        RETRYABLE_STATE_MISSING,
        POISONED
    }

    public enum ReconciliationClaimDecision {
        CLAIMED,
        NOT_DUE,
        TERMINAL,
        OWNERSHIP_MISMATCH,
        STATE_INVALID,
        RESERVATION_MISMATCH,
        INDEX_MISSING,
        QUARANTINED
    }

    public enum ProcessingBackfillDecision {
        INDEXED,
        ALREADY_INDEXED,
        TERMINAL,
        OWNERSHIP_MISMATCH,
        STATE_INVALID,
        RESERVATION_MISMATCH,
        QUARANTINED
    }

    public static final class ReconciliationClaim {
        private final ReconciliationClaimDecision decision;
        private final long redisNow;
        private final Long createdAt;
        private final long reconcileAttempts;

        public ReconciliationClaim(ReconciliationClaimDecision decision, long redisNow,
                                   Long createdAt, long reconcileAttempts) {
            this.decision = decision;
            this.redisNow = redisNow;
            this.createdAt = createdAt;
            this.reconcileAttempts = reconcileAttempts;
        }

        public ReconciliationClaimDecision getDecision() {
            return decision;
        }

        public long getRedisNow() {
            return redisNow;
        }

        public Long getCreatedAt() {
            return createdAt;
        }

        public long getReconcileAttempts() {
            return reconcileAttempts;
        }
    }
}
