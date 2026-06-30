package com.localdeals.websocket;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages WebSocket sessions for seckill order result push. Sessions are keyed by userId
 * (extracted during the handshake by {@link WebSocketAuthInterceptor}) so that
 * {@link WebSocketNotifier} results delivered via Redis pub/sub can be routed to the
 * correct connected client.
 */
@Slf4j
@Component
public class SeckillWebSocketHandler extends TextWebSocketHandler {

    private final Map<Long, WebSocketSession> sessions = new ConcurrentHashMap<>();

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        Long userId = (Long) session.getAttributes().get("userId");
        if (userId != null) {
            sessions.put(userId, session);
            log.debug("WebSocket connected. userId={}, sessionId={}", userId, session.getId());
        } else {
            log.warn("WebSocket connection established without userId attribute. sessionId={}", session.getId());
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        Long userId = (Long) session.getAttributes().get("userId");
        if (userId != null) {
            sessions.remove(userId, session);
            log.debug("WebSocket disconnected. userId={}, sessionId={}, status={}", userId, session.getId(), status);
        }
    }

    /**
     * Sends a text message to the session registered for the given userId, if any.
     *
     * @return true if a message was sent, false if no open session was found
     */
    public boolean sendToUser(Long userId, String message) {
        WebSocketSession session = sessions.get(userId);
        if (session == null || !session.isOpen()) {
            log.debug("No open WebSocket session for userId={}", userId);
            return false;
        }
        try {
            session.sendMessage(new TextMessage(message));
            return true;
        } catch (Exception e) {
            log.warn("Failed to send WebSocket message. userId={}", userId, e);
            sessions.remove(userId, session);
            return false;
        }
    }

    /**
     * Broadcasts a message to every currently open session (used for admin-side seckill result feed).
     */
    public void sendToAll(String message) {
        sessions.values().forEach(session -> {
            if (!session.isOpen()) return;
            try {
                session.sendMessage(new TextMessage(message));
            } catch (Exception e) {
                log.warn("Failed to broadcast WebSocket message. sessionId={}", session.getId(), e);
            }
        });
    }

    public int getOnlineCount() {
        return (int) sessions.values().stream().filter(WebSocketSession::isOpen).count();
    }

    /**
     * Exposes the internal sessions map for test use only (e.g. to register a mock session
     * directly without going through a real handshake).
     */
    public Map<Long, WebSocketSession> getSessionsForTest() {
        return sessions;
    }
}
