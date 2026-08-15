package com.localdeals.websocket;

import cn.hutool.json.JSONUtil;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.util.HashMap;
import java.util.Map;

/**
 * Publishes seckill order results to Redis pub/sub so that any application instance holding
 * the user's open WebSocket session can forward the message. This decouples the RocketMQ
 * consumer (which may run on a different node than the user's WebSocket connection) from the
 * WebSocket session itself.
 */
@Component
public class WebSocketNotifier {

    public static final String CHANNEL_PREFIX = "ws:seckill:";
    public static final String ADMIN_CHANNEL = "ws:seckill:admin";

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    public void notify(Long userId, boolean success, Long orderId, Long voucherId) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("type", "SECKILL_RESULT");
        payload.put("success", success);
        // Redis-generated ids exceed JavaScript's safe integer range; keep the wire contract exact.
        payload.put("orderId", orderId.toString());
        payload.put("voucherId", voucherId);
        payload.put("message", success ? "秒杀成功，订单已生成" : "下单失败，预占已释放");
        String json = JSONUtil.toJsonStr(payload);
        // 推送给下单用户
        stringRedisTemplate.convertAndSend(CHANNEL_PREFIX + userId, json);
        // 广播给所有管理端连接（实时订单面板）
        stringRedisTemplate.convertAndSend(ADMIN_CHANNEL, json);
    }
}
