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

    private static final String CHANNEL_PREFIX = "ws:seckill:";

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    public void notify(Long userId, boolean success, Long orderId, Long voucherId) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("type", "SECKILL_RESULT");
        payload.put("success", success);
        payload.put("orderId", orderId);
        payload.put("voucherId", voucherId);
        payload.put("message", success ? "秒杀成功，订单已生成" : "库存不足");
        String json = JSONUtil.toJsonStr(payload);
        stringRedisTemplate.convertAndSend(CHANNEL_PREFIX + userId, json);
    }
}
