package com.localdeals.trade.mq;

import cn.hutool.json.JSONUtil;
import com.localdeals.trade.config.SeckillProperties;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.client.producer.SendStatus;
import org.apache.rocketmq.spring.annotation.RocketMQTransactionListener;
import org.apache.rocketmq.spring.core.RocketMQLocalTransactionListener;
import org.apache.rocketmq.spring.core.RocketMQLocalTransactionState;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static com.localdeals.platform.utils.RedisConstants.SECKILL_ORDER_STATUS_KEY;
import static com.localdeals.platform.utils.RedisConstants.SECKILL_PROCESSING_QUARANTINE_KEY;
import static com.localdeals.platform.utils.RedisConstants.SECKILL_RESERVATION_KEY;

/**
 * Publishes an admitted seckill order as a RocketMQ transaction message.
 *
 * <p>The admission already happened on the HTTP thread (one Lua round trip), so only winners
 * get here and the local transaction merely confirms. The broker's check-back still consults the
 * Redis reservation for a half message whose confirmation was lost.</p>
 */
@Slf4j
@Service
@RocketMQTransactionListener(rocketMQTemplateBeanName = "rocketMQTemplate")
public class SeckillOrderProducer implements RocketMQLocalTransactionListener {

    static final String ORDER_STATUS_PROCESSING = "PROCESSING";
    static final String ORDER_STATUS_SUCCESS = "SUCCESS";
    static final String ORDER_STATUS_FAILED = "FAILED";

    @Resource
    private RocketMQTemplate rocketMQTemplate;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private SeckillProperties seckillProperties;

    public SeckillOrderProducer() {
    }

    /** Stub for the red commit. */
    public SeckillOrderProducer(RocketMQTemplate rocketMQTemplate, SeckillProperties seckillProperties) {
        this.rocketMQTemplate = rocketMQTemplate;
        this.seckillProperties = seckillProperties;
    }

    /** @throws RuntimeException when the broker did not store the message */
    public void publish(SeckillOrderMessage message) {
        SendResult result = rocketMQTemplate.sendMessageInTransaction(
                seckillProperties.getTopic(), MessageBuilder.withPayload(message).build(), null);
        if (result == null || result.getSendStatus() != SendStatus.SEND_OK) {
            throw new IllegalStateException("Seckill order message was not stored. orderId=" +
                    message.getOrderId() + ", status=" + (result == null ? null : result.getSendStatus()));
        }
    }

    @Override
    public RocketMQLocalTransactionState executeLocalTransaction(Message msg, Object arg) {
        return RocketMQLocalTransactionState.COMMIT;
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
}
