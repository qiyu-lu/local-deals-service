package com.localdeals.websocket;

import com.localdeals.config.AdminProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages isolated WebSocket session registries for user receipts and admin broadcasts.
 */
@Slf4j
@Component
public class SeckillWebSocketHandler extends TextWebSocketHandler {

    private final Map<Long, WebSocketSession> userSessions = new ConcurrentHashMap<>();
    private final Map<String, WebSocketSession> adminSessions = new ConcurrentHashMap<>();
    private final WebSocketAuthInterceptor webSocketAuthInterceptor;
    private final AdminProperties adminProperties;

    public SeckillWebSocketHandler(WebSocketAuthInterceptor webSocketAuthInterceptor,
            AdminProperties adminProperties) {
        this.webSocketAuthInterceptor = webSocketAuthInterceptor;
        this.adminProperties = adminProperties;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        Long userId = (Long) session.getAttributes().get(WebSocketAuthInterceptor.USER_ID_ATTRIBUTE);
        String connectionType = (String) session.getAttributes()
                .get(WebSocketAuthInterceptor.CONNECTION_TYPE_ATTRIBUTE);
        if (WebSocketAuthInterceptor.CONNECTION_TYPE_ADMIN.equals(connectionType)) {
            adminSessions.put(session.getId(), session);
            log.debug("Admin WebSocket connected. userId={}, sessionId={}", userId, session.getId());
            return;
        }
        if (userId != null) {
            userSessions.put(userId, session);
            log.debug("User WebSocket connected. userId={}, sessionId={}", userId, session.getId());
        } else {
            log.warn("WebSocket connection established without userId attribute. sessionId={}", session.getId());
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        String connectionType = (String) session.getAttributes()
                .get(WebSocketAuthInterceptor.CONNECTION_TYPE_ATTRIBUTE);
        if (WebSocketAuthInterceptor.CONNECTION_TYPE_ADMIN.equals(connectionType)) {
            adminSessions.remove(session.getId(), session);
            log.debug("Admin WebSocket disconnected. sessionId={}, status={}", session.getId(), status);
            return;
        }
        Long userId = (Long) session.getAttributes().get(WebSocketAuthInterceptor.USER_ID_ATTRIBUTE);
        if (userId != null) {
            userSessions.remove(userId, session);
            log.debug("User WebSocket disconnected. userId={}, sessionId={}, status={}",
                    userId, session.getId(), status);
        }
    }

    /**
     * Sends a text message to the session registered for the given userId, if any.
     *
     * @return true if a message was sent, false if no open session was found
     */
    public boolean sendToUser(Long userId, String message) {
        WebSocketSession session = userSessions.get(userId);
        if (session == null || !session.isOpen()) {
            log.debug("No open WebSocket session for userId={}", userId);
            return false;
        }
        if (!isCurrentUserSession(session, userId)) {
            closeRevokedUserSession(userId, session);
            return false;
        }
        try {
            session.sendMessage(new TextMessage(message));
            return true;
        } catch (Exception e) {
            log.warn("Failed to send WebSocket message. userId={}", userId, e);
            userSessions.remove(userId, session);
            return false;
        }
    }

    private boolean isCurrentUserSession(WebSocketSession session, Long expectedUserId) {
        Long sessionUserId = (Long) session.getAttributes().get(WebSocketAuthInterceptor.USER_ID_ATTRIBUTE);
        String token = (String) session.getAttributes().get(WebSocketAuthInterceptor.TOKEN_ATTRIBUTE);
        return expectedUserId != null && expectedUserId.equals(sessionUserId) &&
                webSocketAuthInterceptor.isTokenValidForUser(token, expectedUserId);
    }

    private void closeRevokedUserSession(Long userId, WebSocketSession session) {
        userSessions.remove(userId, session);
        closeRevokedSession(session, "userId=" + userId);
    }

    /**
     * Broadcasts a message only to sessions authenticated through the admin endpoint.
     */
    public void sendToAdmins(String message) {
        adminSessions.forEach((sessionId, session) -> {
            if (!session.isOpen()) {
                adminSessions.remove(sessionId, session);
                return;
            }
            if (!isCurrentAdminSession(session)) {
                closeRevokedAdminSession(sessionId, session);
                return;
            }
            try {
                session.sendMessage(new TextMessage(message));
            } catch (Exception e) {
                log.warn("Failed to send admin WebSocket message. sessionId={}", session.getId(), e);
                adminSessions.remove(sessionId, session);
            }
        });
    }

    private boolean isCurrentAdminSession(WebSocketSession session) {
        Long userId = (Long) session.getAttributes().get(WebSocketAuthInterceptor.USER_ID_ATTRIBUTE);
        String token = (String) session.getAttributes().get(WebSocketAuthInterceptor.TOKEN_ATTRIBUTE);
        return adminProperties.isAdminUser(userId) &&
                webSocketAuthInterceptor.isTokenValidForUser(token, userId);
    }

    private void closeRevokedAdminSession(String sessionId, WebSocketSession session) {
        adminSessions.remove(sessionId, session);
        closeRevokedSession(session, "sessionId=" + sessionId);
    }

    private void closeRevokedSession(WebSocketSession session, String identity) {
        try {
            session.close(CloseStatus.POLICY_VIOLATION);
        } catch (Exception e) {
            log.debug("Failed to close revoked WebSocket session. {}", identity, e);
        }
        log.warn("Revoked WebSocket session. {}", identity);
    }

    public int getOnlineCount() {
        return (int) userSessions.values().stream().filter(WebSocketSession::isOpen).count();
    }

    public int getAdminOnlineCount() {
        return (int) adminSessions.values().stream().filter(WebSocketSession::isOpen).count();
    }

    /**
     * Exposes the internal sessions map for test use only (e.g. to register a mock session
     * directly without going through a real handshake).
     */
    Map<Long, WebSocketSession> getUserSessionsForTest() {
        return userSessions;
    }

    Map<String, WebSocketSession> getAdminSessionsForTest() {
        return adminSessions;
    }
}
