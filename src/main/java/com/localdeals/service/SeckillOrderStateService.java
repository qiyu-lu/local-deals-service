package com.localdeals.service;

import com.localdeals.mq.SeckillOrderMessage;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

import static com.localdeals.utils.RedisConstants.SECKILL_META_KEY;
import static com.localdeals.utils.RedisConstants.SECKILL_ORDER_KEY;
import static com.localdeals.utils.RedisConstants.SECKILL_ORDER_STATUS_KEY;
import static com.localdeals.utils.RedisConstants.SECKILL_ORDER_STATUS_TTL_SECONDS;
import static com.localdeals.utils.RedisConstants.SECKILL_RESERVATION_KEY;
import static com.localdeals.utils.RedisConstants.SECKILL_STOCK_KEY;

/** Owns the Redis state transitions which follow seckill admission. */
@Service
public class SeckillOrderStateService {

    public static final String STATUS_PROCESSING = "PROCESSING";
    public static final String STATUS_SUCCESS = "SUCCESS";
    public static final String STATUS_FAILED = "FAILED";

    private static final DefaultRedisScript<Long> MARK_SUCCESS_SCRIPT;
    private static final DefaultRedisScript<Long> COMPENSATE_SCRIPT;
    private static final DefaultRedisScript<Long> VALIDATE_RESERVATION_SCRIPT;

    static {
        MARK_SUCCESS_SCRIPT = script("lua/seckill_mark_success.lua");
        COMPENSATE_SCRIPT = script("lua/seckill_compensate.lua");
        VALIDATE_RESERVATION_SCRIPT = script("lua/seckill_validate_reservation.lua");
    }

    private final StringRedisTemplate stringRedisTemplate;

    public SeckillOrderStateService(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
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
                        reservationKey(message.getVoucherId())),
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
                        reservationKey(message.getVoucherId())),
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
                        orderStatusKey(message.getOrderId())),
                message.getUserId().toString(),
                message.getVoucherId().toString(),
                message.getOrderId().toString(),
                reason,
                SECKILL_ORDER_STATUS_TTL_SECONDS.toString()
        );
        if (Long.valueOf(1L).equals(result)) {
            return true;
        }
        Snapshot snapshot = find(message.getOrderId());
        return snapshot != null && snapshot.belongsTo(message) && STATUS_FAILED.equals(snapshot.getStatus());
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

    private static Long parseLong(Object value) {
        try {
            return value == null ? null : Long.valueOf(value.toString());
        } catch (NumberFormatException e) {
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
}
