package com.localdeals.config;

import com.localdeals.websocket.SeckillWebSocketHandler;
import com.localdeals.websocket.WebSocketAuthInterceptor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
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

    private static final String CHANNEL_PREFIX = "ws:seckill:";

    @Resource
    private SeckillWebSocketHandler webSocketHandler;

    @Resource
    private WebSocketAuthInterceptor webSocketAuthInterceptor;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(webSocketHandler, "/ws/connect")
                .addInterceptors(webSocketAuthInterceptor)
                // Dev only: restrict to known origins in production.
                // Note: setAllowedOriginPatterns(String) requires Spring 5.3+; this project is on
                // Spring 5.2.15 (Boot 2.3.12), so setAllowedOrigins(String...) is used instead.
                .setAllowedOrigins("*");
    }

    @Bean
    public RedisMessageListenerContainer redisWebSocketListenerContainer() {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(stringRedisTemplate.getConnectionFactory());
        container.addMessageListener(
                (message, pattern) -> {
                    String channel = new String(message.getChannel(), StandardCharsets.UTF_8);
                    String userIdStr = channel.replace(CHANNEL_PREFIX, "");
                    try {
                        Long userId = Long.parseLong(userIdStr);
                        String body = new String(message.getBody(), StandardCharsets.UTF_8);
                        webSocketHandler.sendToUser(userId, body);
                    } catch (NumberFormatException e) {
                        log.warn("Received WebSocket pub/sub message on unexpected channel: {}", channel);
                    }
                },
                new PatternTopic(CHANNEL_PREFIX + "*")
        );
        return container;
    }
}
