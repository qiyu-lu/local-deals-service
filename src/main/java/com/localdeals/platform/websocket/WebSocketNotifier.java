package com.localdeals.platform.websocket;

import cn.hutool.json.JSONUtil;
import com.localdeals.trade.mq.SeckillOrderMessage;
import lombok.extern.slf4j.Slf4j;
import com.localdeals.marketing.entity.VoucherGrantNotificationOutbox;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Publishes seckill order results to Redis pub/sub so that any application instance holding
 * the user's open WebSocket session can forward the message. This decouples the RocketMQ
 * consumer (which may run on a different node than the user's WebSocket connection) from the
 * WebSocket session itself.
 */
@Slf4j
@Component
public class WebSocketNotifier {

    public static final String CHANNEL_PREFIX = "ws:seckill:";
    public static final String VOUCHER_GRANT_CHANNEL_PREFIX = "ws:voucher-grant:";
    public static final String ADMIN_PLATFORM_CHANNEL = "ws:seckill:admin:platform";
    public static final String ADMIN_MERCHANT_CHANNEL_PREFIX = "ws:seckill:admin:merchant:";

    private static final String MERCHANT_BY_VOUCHER_SQL =
            "SELECT s.merchant_id FROM tb_voucher v " +
                    "JOIN tb_shop s ON s.id = v.shop_id WHERE v.id = ?";

    /**
     * A voucher's merchant never changes, and in M0 this lookup was 25% of the consumer's wall
     * clock because every order ran it again while holding a pooled connection. The set of live
     * vouchers is small, so the mapping is memoised per instance.
     */
    private final Map<Long, Long> merchantByVoucher = new ConcurrentHashMap<>();

    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private JdbcTemplate jdbcTemplate;

    public void notify(Long userId, boolean success, Long orderId, Long voucherId) {
        String json = seckillPayload(userId, success, orderId, voucherId);
        // 推送给下单用户
        stringRedisTemplate.convertAndSend(CHANNEL_PREFIX + userId, json);
        // 平台账号拥有全局数据范围，但 handler 仍会在发送前复核实时订单权限。
        stringRedisTemplate.convertAndSend(ADMIN_PLATFORM_CHANNEL, json);

        // 商户只能收到归属于自己店铺的订单事件。归属查询失败时保持 fail-closed：
        // 用户回执和平台事件不受影响，但绝不退化为全商户广播。
        Long merchantId = resolveMerchantId(voucherId);
        if (merchantId != null) {
            stringRedisTemplate.convertAndSend(ADMIN_MERCHANT_CHANNEL_PREFIX + merchantId, json);
        }
    }

    /**
     * Announces a whole batch of persisted seckill orders in one Redis round trip.
     *
     * <p>Three publishes per order on the consume thread is the kind of per-order work M4 removes;
     * the payloads and channels are exactly the ones {@link #notify} uses. Failure never
     * propagates: the durable status endpoint is authoritative, and a pub/sub notification must
     * never cause an already-finalized DB/Redis transition to be redelivered.</p>
     */
    public void notifySeckillBatch(List<SeckillOrderMessage> orders) {
        if (orders == null || orders.isEmpty()) {
            return;
        }
        List<String[]> messages = new ArrayList<>(orders.size() * 3);
        for (SeckillOrderMessage order : orders) {
            String json = seckillPayload(order.getUserId(), true, order.getOrderId(), order.getVoucherId());
            messages.add(new String[]{CHANNEL_PREFIX + order.getUserId(), json});
            messages.add(new String[]{ADMIN_PLATFORM_CHANNEL, json});
            Long merchantId = resolveMerchantId(order.getVoucherId());
            if (merchantId != null) {
                messages.add(new String[]{ADMIN_MERCHANT_CHANNEL_PREFIX + merchantId, json});
            }
        }
        try {
            stringRedisTemplate.executePipelined(new SessionCallback<Object>() {
                @Override
                public Object execute(RedisOperations operations) {
                    for (String[] message : messages) {
                        operations.convertAndSend(message[0], message[1]);
                    }
                    return null;
                }
            });
        } catch (RuntimeException e) {
            log.warn("Seckill WebSocket batch notification failed. orders={}", orders.size(), e);
        }
    }

    /** Publishes the durable grant notification to the independent user channel. */
    public void notifyVoucherGranted(VoucherGrantNotificationOutbox outbox) {
        if (outbox == null || outbox.getId() == null || outbox.getGrantId() == null ||
                outbox.getCampaignId() == null || outbox.getVoucherId() == null || outbox.getUserId() == null) {
            throw new IllegalArgumentException("优惠券通知字段不完整");
        }
        Map<String, Object> payload = new HashMap<>();
        payload.put("type", "VOUCHER_GRANTED");
        payload.put("eventId", String.valueOf(outbox.getId()));
        payload.put("grantId", String.valueOf(outbox.getGrantId()));
        payload.put("campaignId", String.valueOf(outbox.getCampaignId()));
        payload.put("voucherId", String.valueOf(outbox.getVoucherId()));
        payload.put("message", "优惠券已发放，已加入我的券包");
        stringRedisTemplate.convertAndSend(VOUCHER_GRANT_CHANNEL_PREFIX + outbox.getUserId(),
                JSONUtil.toJsonStr(payload));
    }

    private static String seckillPayload(Long userId, boolean success, Long orderId, Long voucherId) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("type", "SECKILL_RESULT");
        payload.put("success", success);
        // Redis-generated ids exceed JavaScript's safe integer range; keep the wire contract exact.
        payload.put("orderId", orderId.toString());
        payload.put("voucherId", voucherId);
        payload.put("message", success ? "秒杀成功，订单已生成" : "下单失败，预占已释放");
        return JSONUtil.toJsonStr(payload);
    }

    private Long resolveMerchantId(Long voucherId) {
        if (voucherId == null) {
            return null;
        }
        Long cached = merchantByVoucher.get(voucherId);
        if (cached != null) {
            return cached;
        }
        Long resolved = queryMerchantId(voucherId);
        if (resolved != null) {
            merchantByVoucher.put(voucherId, resolved);
        }
        return resolved;
    }

    private Long queryMerchantId(Long voucherId) {
        try {
            return jdbcTemplate.queryForObject(MERCHANT_BY_VOUCHER_SQL, Long.class, voucherId);
        } catch (DataAccessException e) {
            log.error("Unable to resolve merchant for admin WebSocket notification. voucherId={}",
                    voucherId, e);
            return null;
        }
    }
}
