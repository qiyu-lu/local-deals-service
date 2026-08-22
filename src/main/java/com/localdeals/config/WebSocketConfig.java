package com.localdeals.config;

import com.localdeals.websocket.AdminWebSocketAuthInterceptor;
import com.localdeals.websocket.SeckillWebSocketHandler;
import com.localdeals.websocket.WebSocketAuthInterceptor;
import com.localdeals.websocket.WebSocketNotifier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.PatternTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

import javax.annotation.Resource;
import java.nio.charset.StandardCharsets;

/**
 * Registers the seckill WebSocket endpoint and a Redis pub/sub listener that forwards
 * messages published on {@code ws:seckill:*} channels to the matching local WebSocket
 * session, if one is open on this instance.
 */
@Slf4j
@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    @Resource
    private SeckillWebSocketHandler webSocketHandler;

    @Resource
    private WebSocketAuthInterceptor webSocketAuthInterceptor;

    @Resource
    private AdminWebSocketAuthInterceptor adminWebSocketAuthInterceptor;

    @Resource
    private WebSocketProperties webSocketProperties;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(webSocketHandler, "/ws/connect")
                .addInterceptors(webSocketAuthInterceptor)
                .setAllowedOrigins(webSocketProperties.allowedOrigins());
        registry.addHandler(webSocketHandler, "/ws/admin/connect")
                .addInterceptors(adminWebSocketAuthInterceptor)
                .setAllowedOrigins(webSocketProperties.allowedOrigins());
    }

    @Bean
    public RedisMessageListenerContainer redisWebSocketListenerContainer() {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(stringRedisTemplate.getConnectionFactory());
        // 推送给指定用户（下单结果回执）
        container.addMessageListener(
                (message, pattern) -> {
                    String channel = new String(message.getChannel(), StandardCharsets.UTF_8);
                    String userIdStr = channel.substring(WebSocketNotifier.CHANNEL_PREFIX.length());
                    try {
                        Long userId = Long.parseLong(userIdStr);
                        String body = new String(message.getBody(), StandardCharsets.UTF_8);
                        webSocketHandler.sendToUser(userId, body);
                    } catch (NumberFormatException e) {
                        log.warn("Received WebSocket pub/sub message on unexpected channel: {}", channel);
                    }
                },
                new PatternTopic(WebSocketNotifier.CHANNEL_PREFIX + "[0-9]*")
        );
        // Durable voucher-grant notifications use a separate user channel and are only a
        // prompt; the client refreshes /voucher-grants/mine for the persisted truth.
        container.addMessageListener(
                (message, pattern) -> {
                    String channel = new String(message.getChannel(), StandardCharsets.UTF_8);
                    String userIdStr = channel.substring(WebSocketNotifier.VOUCHER_GRANT_CHANNEL_PREFIX.length());
                    try {
                        Long userId = Long.parseLong(userIdStr);
                        String body = new String(message.getBody(), StandardCharsets.UTF_8);
                        webSocketHandler.sendToUser(userId, body);
                    } catch (NumberFormatException e) {
                        log.warn("Received voucher grant message on unexpected channel: {}", channel);
                    }
                },
                new PatternTopic(WebSocketNotifier.VOUCHER_GRANT_CHANNEL_PREFIX + "[0-9]*")
        );
        // 平台实时订单频道。
        container.addMessageListener(
                (message, pattern) -> {
                    String body = new String(message.getBody(), StandardCharsets.UTF_8);
                    webSocketHandler.sendToPlatformAdmins(body);
                },
                new ChannelTopic(WebSocketNotifier.ADMIN_PLATFORM_CHANNEL));
        // 商户频道以 merchantId 精确路由，handler 会再次校验 session 的账号、scope 和权限。
        container.addMessageListener(
                (message, pattern) -> {
                    String channel = new String(message.getChannel(), StandardCharsets.UTF_8);
                    if (!channel.startsWith(WebSocketNotifier.ADMIN_MERCHANT_CHANNEL_PREFIX)) {
                        log.warn("Received WebSocket pub/sub message on unexpected channel: {}", channel);
                        return;
                    }
                    String merchantIdValue = channel.substring(
                            WebSocketNotifier.ADMIN_MERCHANT_CHANNEL_PREFIX.length());
                    try {
                        Long merchantId = Long.valueOf(merchantIdValue);
                        String body = new String(message.getBody(), StandardCharsets.UTF_8);
                        webSocketHandler.sendToMerchantAdmins(merchantId, body);
                    } catch (NumberFormatException e) {
                        log.warn("Received merchant WebSocket message with invalid merchant id: {}", channel);
                    }
                },
                new PatternTopic(WebSocketNotifier.ADMIN_MERCHANT_CHANNEL_PREFIX + "*"));
        return container;
    }
}
