package com.localdeals.mq;

import cn.hutool.json.JSONUtil;
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
import java.util.Arrays;

import static com.localdeals.utils.RedisConstants.SECKILL_ORDER_KEY;
import static com.localdeals.utils.RedisConstants.SECKILL_STOCK_KEY;

/**
 * Sends RocketMQ transaction messages for seckill orders.
 *
 * <p>The HTTP thread calls {@link #sendSeckillTransaction(Long, Long, Long)} which blocks until
 * the local transaction (Redis Lua admission check) has executed on the RocketMQ client thread.
 * The Lua result is handed back to the calling thread via a {@link ThreadLocal}.</p>
 */
@Slf4j
@Service
@RocketMQTransactionListener(rocketMQTemplateBeanName = "rocketMQTemplate")
public class SeckillOrderProducer implements RocketMQLocalTransactionListener {

    private static final String TOPIC = "seckill-order-topic";

    @Resource
    private RocketMQTemplate rocketMQTemplate;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    private final ThreadLocal<Integer> luaResultHolder = new ThreadLocal<>();

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
     * @return 0 = success (message committed), 1 = out of stock, 2 = already purchased
     */
    public int sendSeckillTransaction(Long voucherId, Long userId, Long orderId) {
        try {
            SeckillOrderMessage msg = new SeckillOrderMessage(voucherId, userId, orderId);
            Message<SeckillOrderMessage> message = MessageBuilder.withPayload(msg).build();

            // sendMessageInTransaction blocks until executeLocalTransaction has completed.
            rocketMQTemplate.sendMessageInTransaction(
                    TOPIC,
                    message,
                    new Object[]{voucherId, userId, orderId}
            );

            Integer result = luaResultHolder.get();
            return result != null ? result : -1;
        } catch (Exception e) {
            log.error("sendSeckillTransaction failed, voucherId={} userId={}", voucherId, userId, e);
            return -1;
        } finally {
            luaResultHolder.remove();
        }
    }

    @Override
    public RocketMQLocalTransactionState executeLocalTransaction(Message msg, Object arg) {
        Object[] args = (Object[]) arg;
        Long voucherId = (Long) args[0];
        Long userId = (Long) args[1];
        Long orderId = (Long) args[2];

        Long luaResult = stringRedisTemplate.execute(
                SECKILL_CHECK_SCRIPT,
                Arrays.asList(SECKILL_STOCK_KEY + voucherId, SECKILL_ORDER_KEY + voucherId),
                userId.toString(), voucherId.toString(), orderId.toString()
        );

        int r = luaResult == null ? -1 : luaResult.intValue();
        luaResultHolder.set(r);

        if (r == 0) {
            log.debug("Seckill Lua check passed. voucherId={}, userId={}", voucherId, userId);
            return RocketMQLocalTransactionState.COMMIT;
        }
        log.debug("Seckill Lua check rejected. voucherId={}, userId={}, reason={}", voucherId, userId, r);
        return RocketMQLocalTransactionState.ROLLBACK;
    }

    @Override
    public RocketMQLocalTransactionState checkLocalTransaction(Message msg) {
        // Broker callback when commit/rollback ack wasn't received in time: check whether the
        // user is already recorded in the Redis order set (Lua adds them only on success).
        try {
            SeckillOrderMessage payload = JSONUtil.toBean(
                    new String((byte[]) msg.getPayload()), SeckillOrderMessage.class);
            Boolean inSet = stringRedisTemplate.opsForSet()
                    .isMember(SECKILL_ORDER_KEY + payload.getVoucherId(), payload.getUserId().toString());
            return Boolean.TRUE.equals(inSet)
                    ? RocketMQLocalTransactionState.COMMIT
                    : RocketMQLocalTransactionState.UNKNOWN;
        } catch (Exception e) {
            log.error("checkLocalTransaction error, returning UNKNOWN", e);
            return RocketMQLocalTransactionState.UNKNOWN;
        }
    }
}
