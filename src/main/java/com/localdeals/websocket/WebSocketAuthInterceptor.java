package com.localdeals.websocket;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.localdeals.dto.UserDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.util.Map;

import static com.localdeals.utils.RedisConstants.LOGIN_USER_KEY;

/**
 * Validates the {@code token} query parameter on the WebSocket handshake against the same
 * login-token hash used by HTTP requests, and stores the resolved userId in the session
 * attributes so {@link SeckillWebSocketHandler} can register the session by userId.
 */
@Slf4j
@Component
public class WebSocketAuthInterceptor implements HandshakeInterceptor {

    public static final String USER_ID_ATTRIBUTE = "userId";
    public static final String TOKEN_ATTRIBUTE = "authToken";
    public static final String CONNECTION_TYPE_ATTRIBUTE = "connectionType";
    public static final String CONNECTION_TYPE_USER = "USER";
    public static final String CONNECTION_TYPE_ADMIN = "ADMIN";

    private final StringRedisTemplate stringRedisTemplate;

    public WebSocketAuthInterceptor(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
            WebSocketHandler wsHandler, Map<String, Object> attributes) {
        if (!authenticate(request, attributes)) {
            return false;
        }
        attributes.put(CONNECTION_TYPE_ATTRIBUTE, CONNECTION_TYPE_USER);
        return true;
    }

    boolean authenticate(ServerHttpRequest request, Map<String, Object> attributes) {
        if (!(request instanceof ServletServerHttpRequest)) {
            return false;
        }
        String token = ((ServletServerHttpRequest) request).getServletRequest().getParameter("token");
        if (StrUtil.isBlank(token)) {
            log.debug("WebSocket handshake rejected: missing token");
            return false;
        }

        Long userId = resolveUserId(token);
        if (userId == null) {
            log.debug("WebSocket handshake rejected: invalid or expired token");
            return false;
        }
        attributes.put(USER_ID_ATTRIBUTE, userId);
        attributes.put(TOKEN_ATTRIBUTE, token);
        return true;
    }

    /**
     * Revalidates a session-bound token without refreshing its TTL. Admin broadcasts use this
     * check so logout and token expiry revoke an already-established privileged connection.
     */
    boolean isTokenValidForUser(String token, Long expectedUserId) {
        if (expectedUserId == null || StrUtil.isBlank(token)) {
            return false;
        }
        return expectedUserId.equals(resolveUserId(token));
    }

    private Long resolveUserId(String token) {
        if (StrUtil.isBlank(token)) {
            return null;
        }
        try {
            Map<Object, Object> userMap = stringRedisTemplate.opsForHash().entries(LOGIN_USER_KEY + token);
            if (userMap == null || userMap.isEmpty()) {
                return null;
            }
            UserDTO user = BeanUtil.fillBeanWithMap(userMap, new UserDTO(), false);
            return user.getId();
        } catch (RuntimeException e) {
            log.warn("Failed to validate WebSocket token against Redis", e);
            return null;
        }
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
            WebSocketHandler wsHandler, Exception exception) {
        // no-op
    }
}
