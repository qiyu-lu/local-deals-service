package com.localdeals.websocket;

import com.localdeals.config.AdminProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.util.Map;

import static com.localdeals.websocket.WebSocketAuthInterceptor.CONNECTION_TYPE_ADMIN;
import static com.localdeals.websocket.WebSocketAuthInterceptor.CONNECTION_TYPE_ATTRIBUTE;
import static com.localdeals.websocket.WebSocketAuthInterceptor.TOKEN_ATTRIBUTE;
import static com.localdeals.websocket.WebSocketAuthInterceptor.USER_ID_ATTRIBUTE;

/**
 * Adds an explicit, fail-closed allowlist check for the admin WebSocket endpoint.
 */
@Slf4j
@Component
public class AdminWebSocketAuthInterceptor implements HandshakeInterceptor {

    private final WebSocketAuthInterceptor userAuthInterceptor;
    private final AdminProperties adminProperties;

    public AdminWebSocketAuthInterceptor(WebSocketAuthInterceptor userAuthInterceptor,
            AdminProperties adminProperties) {
        this.userAuthInterceptor = userAuthInterceptor;
        this.adminProperties = adminProperties;
    }

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
            WebSocketHandler wsHandler, Map<String, Object> attributes) {
        if (!userAuthInterceptor.authenticate(request, attributes)) {
            return false;
        }

        Long userId = (Long) attributes.get(USER_ID_ATTRIBUTE);
        if (!adminProperties.isAdminUser(userId)) {
            attributes.remove(USER_ID_ATTRIBUTE);
            attributes.remove(TOKEN_ATTRIBUTE);
            attributes.remove(CONNECTION_TYPE_ATTRIBUTE);
            log.warn("Admin WebSocket handshake rejected: userId={} is not allowlisted", userId);
            return false;
        }

        attributes.put(CONNECTION_TYPE_ATTRIBUTE, CONNECTION_TYPE_ADMIN);
        return true;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
            WebSocketHandler wsHandler, Exception exception) {
        // no-op
    }
}
