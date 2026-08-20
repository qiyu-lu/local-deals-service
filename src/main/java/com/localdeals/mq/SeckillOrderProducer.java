package com.localdeals.mq;

import cn.hutool.json.JSONUtil;
import com.localdeals.config.SeckillProperties;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQTransactionListener;
import org.apache.rocketmq.spring.core.RocketMQLocalTransactionListener;
import org.apache.rocketmq.spring.core.RocketMQLocalTransactionState;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static com.localdeals.utils.RedisConstants.SECKILL_META_KEY;
import static com.localdeals.utils.RedisConstants.SECKILL_ORDER_KEY;
import static com.localdeals.utils.RedisConstants.SECKILL_ORDER_STATUS_KEY;
import static com.localdeals.utils.RedisConstants.SECKILL_PROCESSING_INDEX_KEY;
import static com.localdeals.utils.RedisConstants.SECKILL_PROCESSING_QUARANTINE_KEY;
import static com.localdeals.utils.RedisConstants.SECKILL_RESERVATION_KEY;
import static com.localdeals.utils.RedisConstants.SECKILL_STOCK_KEY;

/**
 * Sends RocketMQ transaction messages for seckill orders.
 *
 * <p>The HTTP thread calls {@link #sendSeckillTransaction(Long, Long, Long)} which blocks until
 * the local transaction (Redis Lua admission check) has completed. The context passed to
 * RocketMQ carries the admission result back without relying on thread affinity.</p>
 */
@Slf4j
@Service
@RocketMQTransactionListener(rocketMQTemplateBeanName = "rocketMQTemplate")
public class SeckillOrderProducer implements RocketMQLocalTransactionListener {

    private static final String TOPIC = "seckill-order-topic";

    public static final int ADMISSION_SYSTEM_ERROR = -1;
    public static final int ADMISSION_ACCEPTED = 0;
    public static final int ADMISSION_OUT_OF_STOCK = 1;
    public static final int ADMISSION_DUPLICATE = 2;
    public static final int ADMISSION_NOT_STARTED = 3;
    public static final int ADMISSION_ENDED_OR_DISABLED = 4;
    public static final int ADMISSION_META_NOT_READY = 5;

    static final String ORDER_STATUS_PROCESSING = "PROCESSING";
    static final String ORDER_STATUS_SUCCESS = "SUCCESS";
    static final String ORDER_STATUS_FAILED = "FAILED";

    @Resource
    private RocketMQTemplate rocketMQTemplate;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private SeckillProperties seckillProperties;

    private static final DefaultRedisScript<Long> SECKILL_CHECK_SCRIPT;

    static {
        SECKILL_CHECK_SCRIPT = new DefaultRedisScript<>();
        SECKILL_CHECK_SCRIPT.setLocation(new ClassPathResource("lua/seckill_check.lua"));
        SECKILL_CHECK_SCRIPT.setResultType(Long.class);
    }

    /**
     * Called from the HTTP thread: sends a transaction message; internally runs the Lua
     * admission check and synchronously returns the Lua result.
     *
     * @return 0 = accepted, 1 = out of stock, 2 = duplicate, 3 = not started,
     *         4 = ended/disabled, 5 = metadata not ready, -1 = infrastructure error
     */
    public int sendSeckillTransaction(Long voucherId, Long userId, Long orderId) {
        LocalTransactionContext context = new LocalTransactionContext(voucherId, userId, orderId);
        try {
            SeckillOrderMessage msg = new SeckillOrderMessage(voucherId, userId, orderId);
            Message<SeckillOrderMessage> message = MessageBuilder.withPayload(msg).build();

            // sendMessageInTransaction blocks until executeLocalTransaction has completed.
            rocketMQTemplate.sendMessageInTransaction(
                    TOPIC,
                    message,
                    context
            );
            return context.getAdmissionResult();
        } catch (Exception e) {
            log.error("sendSeckillTransaction failed, voucherId={} userId={}", voucherId, userId, e);
            return ADMISSION_SYSTEM_ERROR;
        }
    }

    @Override
    public RocketMQLocalTransactionState executeLocalTransaction(Message msg, Object arg) {
        if (!(arg instanceof LocalTransactionContext)) {
            log.error("Unexpected RocketMQ local transaction argument: {}", arg);
            return RocketMQLocalTransactionState.UNKNOWN;
        }
        LocalTransactionContext context = (LocalTransactionContext) arg;
        Long voucherId = context.getVoucherId();
        Long userId = context.getUserId();
        Long orderId = context.getOrderId();

        final Long luaResult;
        try {
            luaResult = stringRedisTemplate.execute(
                    SECKILL_CHECK_SCRIPT,
                    Arrays.asList(
                            SECKILL_STOCK_KEY + voucherId,
                            SECKILL_ORDER_KEY + voucherId,
                            SECKILL_META_KEY + voucherId,
                            SECKILL_RESERVATION_KEY + voucherId,
                            SECKILL_ORDER_STATUS_KEY + orderId,
                            SECKILL_PROCESSING_INDEX_KEY),
                    userId.toString(),
                    voucherId.toString(),
                    orderId.toString(),
                    Long.toString(seckillProperties.getReconciliation().getStaleAfter().getSeconds())
            );
        } catch (Exception e) {
            context.setAdmissionResult(ADMISSION_SYSTEM_ERROR);
            log.error("Seckill Lua check failed. voucherId={}, userId={}, orderId={}",
                    voucherId, userId, orderId, e);
            return RocketMQLocalTransactionState.UNKNOWN;
        }

        int result = luaResult == null ? ADMISSION_SYSTEM_ERROR : luaResult.intValue();
        context.setAdmissionResult(result);
        if (result == ADMISSION_ACCEPTED) {
            log.debug("Seckill Lua check passed. voucherId={}, userId={}, orderId={}",
                    voucherId, userId, orderId);
            return RocketMQLocalTransactionState.COMMIT;
        }
        if (isBusinessRejection(result)) {
            log.debug("Seckill Lua check rejected. voucherId={}, userId={}, reason={}",
                    voucherId, userId, result);
            return RocketMQLocalTransactionState.ROLLBACK;
        }
        log.error("Seckill Lua returned an unknown result. voucherId={}, userId={}, result={}",
                voucherId, userId, result);
        return RocketMQLocalTransactionState.UNKNOWN;
    }

    @Override
    public RocketMQLocalTransactionState checkLocalTransaction(Message msg) {
        final SeckillOrderMessage payload;
        try {
            payload = readPayload(msg);
        } catch (Exception e) {
            log.error("Cannot parse RocketMQ transaction payload; rolling back malformed message", e);
            return RocketMQLocalTransactionState.ROLLBACK;
        }
        if (payload == null || payload.getVoucherId() == null
                || payload.getUserId() == null || payload.getOrderId() == null) {
            return RocketMQLocalTransactionState.ROLLBACK;
        }

        try {
            Double quarantineScore = stringRedisTemplate.opsForZSet().score(
                    SECKILL_PROCESSING_QUARANTINE_KEY, payload.getOrderId().toString());
            if (quarantineScore != null) {
                log.error("Rolling back quarantined seckill transaction. orderId={}", payload.getOrderId());
                return RocketMQLocalTransactionState.ROLLBACK;
            }
            Object reservedOrderId = stringRedisTemplate.opsForHash().get(
                    SECKILL_RESERVATION_KEY + payload.getVoucherId(),
                    payload.getUserId().toString());
            if (reservedOrderId == null
                    || !payload.getOrderId().toString().equals(reservedOrderId.toString())) {
                return RocketMQLocalTransactionState.ROLLBACK;
            }

            java.util.List<Object> statusData = stringRedisTemplate.opsForHash().multiGet(
                    SECKILL_ORDER_STATUS_KEY + payload.getOrderId(),
                    Arrays.asList("status", "orderId", "userId", "voucherId"));
            if (statusData == null || statusData.size() != 4 || statusData.get(0) == null) {
                return RocketMQLocalTransactionState.UNKNOWN;
            }
            if (!payload.getOrderId().toString().equals(stringValue(statusData.get(1)))
                    || !payload.getUserId().toString().equals(stringValue(statusData.get(2)))
                    || !payload.getVoucherId().toString().equals(stringValue(statusData.get(3)))) {
                return RocketMQLocalTransactionState.ROLLBACK;
            }
            String statusValue = statusData.get(0).toString();
            if (ORDER_STATUS_PROCESSING.equals(statusValue) || ORDER_STATUS_SUCCESS.equals(statusValue)) {
                return RocketMQLocalTransactionState.COMMIT;
            }
            if (ORDER_STATUS_FAILED.equals(statusValue)) {
                return RocketMQLocalTransactionState.ROLLBACK;
            }
            return RocketMQLocalTransactionState.UNKNOWN;
        } catch (Exception e) {
            log.error("Redis unavailable during transaction check; returning UNKNOWN", e);
            return RocketMQLocalTransactionState.UNKNOWN;
        }
    }

    private static boolean isBusinessRejection(int result) {
        return result >= ADMISSION_OUT_OF_STOCK && result <= ADMISSION_META_NOT_READY;
    }

    private static String stringValue(Object value) {
        return value == null ? null : value.toString();
    }

    private static SeckillOrderMessage readPayload(Message msg) {
        Object rawPayload = msg.getPayload();
        if (rawPayload instanceof SeckillOrderMessage) {
            return (SeckillOrderMessage) rawPayload;
        }
        String json;
        if (rawPayload instanceof byte[]) {
            json = new String((byte[]) rawPayload, StandardCharsets.UTF_8);
        } else if (rawPayload instanceof String) {
            json = (String) rawPayload;
        } else {
            json = JSONUtil.toJsonStr(rawPayload);
        }
        return JSONUtil.toBean(json, SeckillOrderMessage.class);
    }

    static final class LocalTransactionContext {
        private final Long voucherId;
        private final Long userId;
        private final Long orderId;
        private volatile int admissionResult = ADMISSION_SYSTEM_ERROR;

        LocalTransactionContext(Long voucherId, Long userId, Long orderId) {
            this.voucherId = voucherId;
            this.userId = userId;
            this.orderId = orderId;
        }

        Long getVoucherId() {
            return voucherId;
        }

        Long getUserId() {
            return userId;
        }

        Long getOrderId() {
            return orderId;
        }

        int getAdmissionResult() {
            return admissionResult;
        }

        void setAdmissionResult(int admissionResult) {
            this.admissionResult = admissionResult;
        }
    }
}
