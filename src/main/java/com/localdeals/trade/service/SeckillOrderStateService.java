package com.localdeals.trade.service;

import com.localdeals.trade.config.SeckillProperties;
import com.localdeals.trade.mq.SeckillOrderMessage;
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
import java.util.UUID;

import static com.localdeals.platform.utils.RedisConstants.SECKILL_ORDER_STATUS_TTL_SECONDS;

/**
 * Owns the Redis state transitions which follow seckill admission.
 *
 * <p>Every script runs inside one stock bucket, so its keys share a hash tag and one Cluster
 * slot. A batch may carry buyers of different buckets, so it is split per bucket and each part
 * is one round trip; the answers are stitched back into the caller's order.</p>
 */
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
    private static final DefaultRedisScript<Long> RECONCILE_DEFER_UNRESOLVED_SCRIPT;
    @SuppressWarnings("rawtypes")
    private static final DefaultRedisScript<List> BATCH_CLAIM_SCRIPT;
    @SuppressWarnings("rawtypes")
    private static final DefaultRedisScript<List> BATCH_MARK_SUCCESS_SCRIPT;

    static {
        MARK_SUCCESS_SCRIPT = script("lua/seckill_mark_success.lua");
        COMPENSATE_SCRIPT = script("lua/seckill_compensate.lua");
        VALIDATE_RESERVATION_SCRIPT = script("lua/seckill_validate_reservation.lua");
        RECONCILE_DUE_SCRIPT = listScript("lua/seckill_reconcile_due.lua");
        RECONCILE_CLAIM_SCRIPT = listScript("lua/seckill_reconcile_claim.lua");
        RECONCILE_DEFER_UNRESOLVED_SCRIPT = script("lua/seckill_reconcile_defer_unresolved.lua");
        BATCH_CLAIM_SCRIPT = listScript("lua/seckill_batch_claim.lua");
        BATCH_MARK_SUCCESS_SCRIPT = listScript("lua/seckill_batch_mark_success.lua");
    }

    private final StringRedisTemplate stringRedisTemplate;
    private final SeckillProperties seckillProperties;
    private final SeckillSoldOutRegistry soldOutRegistry;
    private final SeckillBucketRouter router;
    /** Identifies this JVM's claims; a crashed owner's claims simply expire. */
    private final String claimOwner = "p:" + UUID.randomUUID();

    public SeckillOrderStateService(StringRedisTemplate stringRedisTemplate,
                                    SeckillProperties seckillProperties,
                                    SeckillSoldOutRegistry soldOutRegistry,
                                    SeckillBucketRouter router) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.seckillProperties = seckillProperties;
        this.soldOutRegistry = soldOutRegistry;
        this.router = router;
    }

    /**
     * Atomically classifies a broker message before any MySQL mutation. Only an exact
     * PROCESSING status plus its exact user-to-order reservation is eligible for persistence.
     */
    public ReservationDecision validateForConsumption(SeckillOrderMessage message) {
        Long result = stringRedisTemplate.execute(
                VALIDATE_RESERVATION_SCRIPT,
                Arrays.asList(
                        statusKey(message),
                        reservationKey(message)),
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
                        statusKey(message),
                        reservationKey(message),
                        processingKey(message)),
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
                        router.stockKey(message.getVoucherId(), bucketOf(message)),
                        reservationKey(message),
                        statusKey(message),
                        processingKey(message)),
                message.getUserId().toString(),
                message.getVoucherId().toString(),
                message.getOrderId().toString(),
                reason,
                SECKILL_ORDER_STATUS_TTL_SECONDS.toString()
        );
        if (Long.valueOf(1L).equals(result)) {
            // A unit went back to this bucket: every instance may admit its buyers again.
            soldOutRegistry.clear(message.getVoucherId(), bucketOf(message));
            return true;
        }
        if (Long.valueOf(2L).equals(result)) {
            return true;
        }
        Snapshot snapshot = find(message.getOrderId());
        return snapshot != null && snapshot.belongsTo(message) && STATUS_FAILED.equals(snapshot.getStatus());
    }

    /** The token this instance writes into {@code claimOwner}. */
    public String claimOwner() {
        return claimOwner;
    }

    /**
     * Classifies a whole consumer batch and leases every claimable reservation to this instance
     * in one round trip. While the lease holds, the reconciler leaves the order alone, which is
     * what replaces the per-message Redisson lock used before M4.
     */
    public List<PersistClaim> claimForPersistence(List<SeckillOrderMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return Collections.emptyList();
        }
        PersistClaim[] claims = new PersistClaim[messages.size()];
        for (Map.Entry<Integer, List<Integer>> part : groupByBucket(messages).entrySet()) {
            List<Integer> positions = part.getValue();
            List<String> keys = new ArrayList<>(1 + 2 * positions.size());
            keys.add(router.processingKey(part.getKey()));
            List<Object> args = new ArrayList<>(2 + 3 * positions.size());
            args.add(claimOwner);
            args.add(Long.toString(seckillProperties.getConsume().getClaimLease().getSeconds()));
            for (int position : positions) {
                SeckillOrderMessage message = messages.get(position);
                keys.add(statusKey(message));
                keys.add(reservationKey(message));
                args.add(message.getUserId().toString());
                args.add(message.getVoucherId().toString());
                args.add(message.getOrderId().toString());
            }
            List<?> response = stringRedisTemplate.execute(BATCH_CLAIM_SCRIPT, keys, args.toArray());
            List<Long> codes = requireOneResultPerMessage(response, positions.size(), "claim");
            for (int i = 0; i < positions.size(); i++) {
                claims[positions.get(i)] = persistClaim(codes.get(i));
            }
        }
        return Arrays.asList(claims);
    }

    /** Finalizes every committed order of a batch, releasing its claim, one round trip per bucket. */
    public List<Boolean> markSuccessBatch(List<SeckillOrderMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return Collections.emptyList();
        }
        Boolean[] finalized = new Boolean[messages.size()];
        for (Map.Entry<Integer, List<Integer>> part : groupByBucket(messages).entrySet()) {
            List<Integer> positions = part.getValue();
            List<String> keys = new ArrayList<>(1 + 2 * positions.size());
            keys.add(router.processingKey(part.getKey()));
            List<Object> args = new ArrayList<>(1 + 3 * positions.size());
            args.add(SECKILL_ORDER_STATUS_TTL_SECONDS.toString());
            for (int position : positions) {
                SeckillOrderMessage message = messages.get(position);
                keys.add(statusKey(message));
                keys.add(reservationKey(message));
                args.add(message.getUserId().toString());
                args.add(message.getVoucherId().toString());
                args.add(message.getOrderId().toString());
            }
            List<?> response = stringRedisTemplate.execute(
                    BATCH_MARK_SUCCESS_SCRIPT, keys, args.toArray());
            List<Long> codes = requireOneResultPerMessage(response, positions.size(), "mark-success");
            for (int i = 0; i < positions.size(); i++) {
                finalized[positions.get(i)] = Long.valueOf(1L).equals(codes.get(i));
            }
        }
        return Arrays.asList(finalized);
    }

    /**
     * Splits a batch into the buckets it touches, keeping each message's position so the answers
     * can be stitched back together. A batch of one bucket stays a single round trip.
     */
    private Map<Integer, List<Integer>> groupByBucket(List<SeckillOrderMessage> messages) {
        Map<Integer, List<Integer>> byBucket = new LinkedHashMap<>();
        for (int position = 0; position < messages.size(); position++) {
            SeckillOrderMessage message = messages.get(position);
            requireCompleteMessage(message);
            byBucket.computeIfAbsent(bucketOf(message), bucket -> new ArrayList<>()).add(position);
        }
        return byBucket;
    }

    private static List<Long> requireOneResultPerMessage(List<?> response, int expected, String what) {
        if (response == null || response.size() != expected) {
            throw new IllegalStateException("Redis batch " + what + " returned " +
                    (response == null ? "nothing" : response.size() + " results") +
                    " for " + expected + " messages");
        }
        List<Long> codes = new ArrayList<>(expected);
        for (Object raw : response) {
            if (raw instanceof Number) {
                codes.add(((Number) raw).longValue());
                continue;
            }
            try {
                codes.add(Long.parseLong(redisString(raw)));
            } catch (NumberFormatException e) {
                throw new IllegalStateException(
                        "Unexpected Redis batch " + what + " result: " + raw, e);
            }
        }
        return codes;
    }

    private static PersistClaim persistClaim(Long code) {
        if (code == null) {
            throw new IllegalStateException("Missing Redis batch claim decision");
        }
        switch (code.intValue()) {
            case 0:
                return PersistClaim.STATE_MISSING;
            case 1:
                return PersistClaim.CLAIMED;
            case 2:
                return PersistClaim.ALREADY_SUCCESS;
            case 3:
                return PersistClaim.ALREADY_FAILED;
            case 4:
                return PersistClaim.POISONED;
            case 5:
                return PersistClaim.CLAIM_BUSY;
            default:
                throw new IllegalStateException("Unexpected Redis batch claim decision: " + code);
        }
    }

    /** What a batch claim decided about one message. */
    public enum PersistClaim {
        CLAIMED,
        ALREADY_SUCCESS,
        ALREADY_FAILED,
        POISONED,
        STATE_MISSING,
        CLAIM_BUSY
    }

    /**
     * Returns at most the configured batch size of due order ids without removing them.
     * Malformed raw members cannot be orders; they are dropped from the index so one bad member
     * cannot make every reconciliation round fail at the Java long conversion boundary.
     */
    public List<Long> findDueOrderIds(int limit) {
        if (limit <= 0) {
            throw new IllegalArgumentException("reconciliation due-query limit must be positive");
        }
        int boundedLimit = Math.min(limit, seckillProperties.getReconciliation().getBatchSize());
        // The index is per bucket, so one cycle asks every bucket for its share of the batch.
        int perBucket = Math.max(1, (boundedLimit + router.count() - 1) / router.count());
        List<Long> orderIds = new ArrayList<>(boundedLimit);
        for (int bucket = 0; bucket < router.count() && orderIds.size() < boundedLimit; bucket++) {
            String processingKey = router.processingKey(bucket);
            List<?> rawMembers = stringRedisTemplate.execute(
                    RECONCILE_DUE_SCRIPT,
                    Collections.singletonList(processingKey),
                    Integer.toString(Math.min(perBucket, boundedLimit - orderIds.size())));
            if (rawMembers == null || rawMembers.isEmpty()) {
                continue;
            }
            for (Object rawMember : rawMembers) {
                String member = redisString(rawMember);
                Long orderId = parseCanonicalPositiveLong(member);
                if (orderId != null) {
                    orderIds.add(orderId);
                } else {
                    log.error("Dropping malformed PROCESSING index member. bucket={} member={}",
                            bucket, member);
                    try {
                        stringRedisTemplate.opsForZSet().remove(processingKey, member);
                    } catch (RuntimeException removeFailure) {
                        log.error("Unable to drop malformed PROCESSING index member. member={}",
                                member, removeFailure);
                    }
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
                        statusKey(message),
                        reservationKey(message),
                        processingKey(message)),
                message.getUserId().toString(),
                message.getVoucherId().toString(),
                message.getOrderId().toString(),
                Long.toString(seckillProperties.getReconciliation().getRetryDelay().getSeconds()),
                claimOwner);
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
     * changing its business status. The Lua also removes a concurrently terminal member, so it
     * cannot recreate an index entry after markSuccess wins a race.
     */
    public boolean deferProcessingOrder(Long orderId) {
        if (orderId == null || orderId <= 0L) {
            throw new IllegalArgumentException("orderId must be positive");
        }
        Long result = stringRedisTemplate.execute(
                RECONCILE_DEFER_UNRESOLVED_SCRIPT,
                Arrays.asList(
                        router.statusKeyOfOrder(orderId),
                        router.processingKey(router.bucketOfOrder(orderId))),
                orderId.toString(),
                Long.toString(seckillProperties.getReconciliation().getRetryDelay().getSeconds()));
        return Long.valueOf(1L).equals(result) || Long.valueOf(2L).equals(result);
    }

    /** Fail closed when Redis and DB stock disagree until an operator reconciles the voucher. */
    public void suspendVoucher(Long voucherId, String reason) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("status", "SUSPENDED");
        values.put("suspendReason", reason);
        values.put("updatedAt", Long.toString(Instant.now().getEpochSecond()));
        // The activity metadata is replicated into every bucket, because the admission script
        // may only read keys of the buyer's own slot.
        for (int bucket = 0; bucket < router.count(); bucket++) {
            stringRedisTemplate.opsForHash().putAll(router.metaKey(voucherId, bucket), values);
        }
    }

    public Snapshot find(Long orderId) {
        Map<Object, Object> values =
                stringRedisTemplate.opsForHash().entries(router.statusKeyOfOrder(orderId));
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

    private int bucketOf(SeckillOrderMessage message) {
        return router.bucketOfUser(message.getUserId());
    }

    private String reservationKey(SeckillOrderMessage message) {
        return router.reservationKey(message.getVoucherId(), bucketOf(message));
    }

    private String statusKey(SeckillOrderMessage message) {
        return router.statusKey(message.getOrderId(), bucketOf(message));
    }

    private String processingKey(SeckillOrderMessage message) {
        return router.processingKey(bucketOf(message));
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
        INDEX_MISSING
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
