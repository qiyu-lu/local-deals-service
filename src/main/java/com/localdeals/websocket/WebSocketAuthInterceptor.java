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

import javax.annotation.Resource;
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

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
            WebSocketHandler wsHandler, Map<String, Object> attributes) {
        if (!(request instanceof ServletServerHttpRequest)) {
            return false;
        }
        String token = ((ServletServerHttpRequest) request).getServletRequest().getParameter("token");
        if (StrUtil.isBlank(token)) {
            log.debug("WebSocket handshake rejected: missing token");
            return false;
        }

        Map<Object, Object> userMap = stringRedisTemplate.opsForHash().entries(LOGIN_USER_KEY + token);
        if (userMap.isEmpty()) {
            log.debug("WebSocket handshake rejected: invalid or expired token");
            return false;
        }

        UserDTO user = BeanUtil.fillBeanWithMap(userMap, new UserDTO(), false);
        if (user.getId() == null) {
            log.debug("WebSocket handshake rejected: token resolved to no userId");
            return false;
        }
        attributes.put("userId", user.getId());
        return true;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
            WebSocketHandler wsHandler, Exception exception) {
        // no-op
    }
}
