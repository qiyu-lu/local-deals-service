package com.localdeals.websocket;

import com.localdeals.auth.AdminPermissionCodes;
import com.localdeals.dto.AdminPrincipal;
import com.localdeals.service.AdminSessionService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static com.localdeals.websocket.AdminWebSocketAuthInterceptor.ADMIN_ACCOUNT_ID_ATTRIBUTE;
import static com.localdeals.websocket.AdminWebSocketAuthInterceptor.ADMIN_MERCHANT_ID_ATTRIBUTE;
import static com.localdeals.websocket.AdminWebSocketAuthInterceptor.ADMIN_SCOPE_TYPE_ATTRIBUTE;
import static com.localdeals.websocket.AdminWebSocketAuthInterceptor.ADMIN_TOKEN_ATTRIBUTE;

/**
 * Manages isolated WebSocket registries for user receipts, platform operators and merchants.
 */
@Slf4j
@Component
public class SeckillWebSocketHandler extends TextWebSocketHandler {
    private static final int SEND_TIME_LIMIT_MILLIS = 10_000;
    private static final int SEND_BUFFER_LIMIT_BYTES = 256 * 1024;

    private final Map<Long, WebSocketSession> userSessions = new ConcurrentHashMap<>();
    private final Map<String, WebSocketSession> platformAdminSessions = new ConcurrentHashMap<>();
    private final Map<Long, Map<String, WebSocketSession>> merchantAdminSessions =
            new ConcurrentHashMap<>();
    private final WebSocketAuthInterceptor webSocketAuthInterceptor;
    private final AdminSessionService adminSessionService;

    public SeckillWebSocketHandler(WebSocketAuthInterceptor webSocketAuthInterceptor,
            AdminSessionService adminSessionService) {
        this.webSocketAuthInterceptor = webSocketAuthInterceptor;
        this.adminSessionService = adminSessionService;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        String connectionType = (String) session.getAttributes()
                .get(WebSocketAuthInterceptor.CONNECTION_TYPE_ATTRIBUTE);
        if (WebSocketAuthInterceptor.CONNECTION_TYPE_ADMIN.equals(connectionType)) {
            registerAdminSession(session);
            return;
        }

        Long userId = (Long) session.getAttributes().get(WebSocketAuthInterceptor.USER_ID_ATTRIBUTE);
        if (userId != null) {
            WebSocketSession concurrentSession = concurrentSession(session);
            WebSocketSession previous = userSessions.put(userId, concurrentSession);
            if (previous != null && !previous.getId().equals(concurrentSession.getId())) {
                closeReplacedSession(previous, "userId=" + userId);
            }
            log.debug("User WebSocket connected. userId={}, sessionId={}", userId, session.getId());
        } else {
            log.warn("WebSocket connection established without userId attribute. sessionId={}", session.getId());
        }
    }

    private void registerAdminSession(WebSocketSession session) {
        Long accountId = (Long) session.getAttributes().get(ADMIN_ACCOUNT_ID_ATTRIBUTE);
        Long merchantId = (Long) session.getAttributes().get(ADMIN_MERCHANT_ID_ATTRIBUTE);
        String scopeType = (String) session.getAttributes().get(ADMIN_SCOPE_TYPE_ATTRIBUTE);
        if (accountId == null) {
            closeRevokedSession(session, "admin session without account id");
            return;
        }
        WebSocketSession concurrentSession = concurrentSession(session);
        if (AdminPrincipal.SCOPE_PLATFORM.equals(scopeType) && merchantId == null) {
            platformAdminSessions.put(session.getId(), concurrentSession);
            log.debug("Platform WebSocket connected. accountId={}, sessionId={}", accountId, session.getId());
            return;
        }
        if (AdminPrincipal.SCOPE_MERCHANT.equals(scopeType) && merchantId != null) {
            merchantAdminSessions.computeIfAbsent(merchantId, ignored -> new ConcurrentHashMap<>())
                    .put(session.getId(), concurrentSession);
            log.debug("Merchant WebSocket connected. accountId={}, merchantId={}, sessionId={}",
                    accountId, merchantId, session.getId());
            return;
        }
        closeRevokedSession(session, "invalid admin scope");
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        String connectionType = (String) session.getAttributes()
                .get(WebSocketAuthInterceptor.CONNECTION_TYPE_ATTRIBUTE);
        if (WebSocketAuthInterceptor.CONNECTION_TYPE_ADMIN.equals(connectionType)) {
            removeAdminSession(session);
            log.debug("Admin WebSocket disconnected. sessionId={}, status={}", session.getId(), status);
            return;
        }
        Long userId = (Long) session.getAttributes().get(WebSocketAuthInterceptor.USER_ID_ATTRIBUTE);
        if (userId != null) {
            userSessions.computeIfPresent(userId, (ignored, registered) ->
                    registered.getId().equals(session.getId()) ? null : registered);
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
            closeFailedSession(session, "userId=" + userId);
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

    /** Sends an event only to currently authorized platform sessions. */
    public void sendToPlatformAdmins(String message) {
        sendToAdminRegistry(platformAdminSessions, AdminPrincipal.SCOPE_PLATFORM, null, message);
    }

    /** Sends an event only to currently authorized sessions for the exact merchant. */
    public void sendToMerchantAdmins(Long merchantId, String message) {
        if (merchantId == null) {
            log.warn("Skipped merchant WebSocket broadcast without merchantId");
            return;
        }
        Map<String, WebSocketSession> sessions = merchantAdminSessions.get(merchantId);
        if (sessions == null) {
            return;
        }
        sendToAdminRegistry(sessions, AdminPrincipal.SCOPE_MERCHANT, merchantId, message);
        if (sessions.isEmpty()) {
            merchantAdminSessions.remove(merchantId, sessions);
        }
    }

    private void sendToAdminRegistry(Map<String, WebSocketSession> sessions, String expectedScope,
            Long expectedMerchantId, String message) {
        sessions.forEach((sessionId, session) -> {
            if (!session.isOpen()) {
                sessions.remove(sessionId, session);
                return;
            }
            if (!isCurrentAdminSession(session, expectedScope, expectedMerchantId)) {
                sessions.remove(sessionId, session);
                closeRevokedSession(session, "adminSessionId=" + sessionId);
                return;
            }
            try {
                session.sendMessage(new TextMessage(message));
            } catch (Exception e) {
                log.warn("Failed to send admin WebSocket message. sessionId={}", session.getId(), e);
                sessions.remove(sessionId, session);
                closeFailedSession(session, "adminSessionId=" + sessionId);
            }
        });
    }

    private boolean isCurrentAdminSession(WebSocketSession session, String expectedScope,
            Long expectedMerchantId) {
        Long expectedAccountId = (Long) session.getAttributes().get(ADMIN_ACCOUNT_ID_ATTRIBUTE);
        String token = (String) session.getAttributes().get(ADMIN_TOKEN_ATTRIBUTE);
        AdminPrincipal principal;
        try {
            principal = adminSessionService.resolve(token, false);
        } catch (RuntimeException e) {
            log.warn("Failed to revalidate admin WebSocket session. sessionId={}", session.getId(), e);
            return false;
        }
        if (principal == null || expectedAccountId == null ||
                !expectedAccountId.equals(principal.getAccountId()) ||
                !principal.hasPermission(AdminPermissionCodes.ORDER_REALTIME) ||
                !expectedScope.equals(principal.getScopeType())) {
            return false;
        }
        if (AdminPrincipal.SCOPE_PLATFORM.equals(expectedScope)) {
            return principal.isPlatform() && principal.getMerchantId() == null;
        }
        return expectedMerchantId != null && expectedMerchantId.equals(principal.getMerchantId());
    }

    private void removeAdminSession(WebSocketSession session) {
        String scopeType = (String) session.getAttributes().get(ADMIN_SCOPE_TYPE_ATTRIBUTE);
        Long merchantId = (Long) session.getAttributes().get(ADMIN_MERCHANT_ID_ATTRIBUTE);
        if (AdminPrincipal.SCOPE_PLATFORM.equals(scopeType)) {
            platformAdminSessions.remove(session.getId());
            return;
        }
        if (AdminPrincipal.SCOPE_MERCHANT.equals(scopeType) && merchantId != null) {
            Map<String, WebSocketSession> sessions = merchantAdminSessions.get(merchantId);
            if (sessions != null) {
                sessions.remove(session.getId());
                if (sessions.isEmpty()) {
                    merchantAdminSessions.remove(merchantId, sessions);
                }
            }
        }
    }

    private void closeRevokedSession(WebSocketSession session, String identity) {
        try {
            session.close(CloseStatus.POLICY_VIOLATION);
        } catch (Exception e) {
            log.debug("Failed to close revoked WebSocket session. {}", identity, e);
        }
        log.warn("Revoked WebSocket session. {}", identity);
    }

    private WebSocketSession concurrentSession(WebSocketSession session) {
        if (session instanceof ConcurrentWebSocketSessionDecorator) {
            return session;
        }
        return new ConcurrentWebSocketSessionDecorator(
                session, SEND_TIME_LIMIT_MILLIS, SEND_BUFFER_LIMIT_BYTES);
    }

    private void closeFailedSession(WebSocketSession session, String identity) {
        try {
            session.close(CloseStatus.SERVER_ERROR);
        } catch (Exception closeError) {
            log.debug("Failed to close broken WebSocket session. {}", identity, closeError);
        }
    }

    private void closeReplacedSession(WebSocketSession session, String identity) {
        try {
            session.close(CloseStatus.NORMAL);
        } catch (Exception closeError) {
            log.debug("Failed to close replaced WebSocket session. {}", identity, closeError);
        }
    }

    public int getOnlineCount() {
        return (int) userSessions.values().stream().filter(WebSocketSession::isOpen).count();
    }

    public int getAdminOnlineCount() {
        int platformCount = (int) platformAdminSessions.values().stream()
                .filter(WebSocketSession::isOpen).count();
        int merchantCount = merchantAdminSessions.values().stream()
                .mapToInt(sessions -> (int) sessions.values().stream()
                        .filter(WebSocketSession::isOpen).count())
                .sum();
        return platformCount + merchantCount;
    }

    Map<Long, WebSocketSession> getUserSessionsForTest() {
        return userSessions;
    }

    Map<String, WebSocketSession> getPlatformAdminSessionsForTest() {
        return platformAdminSessions;
    }

    Map<String, WebSocketSession> getMerchantAdminSessionsForTest(Long merchantId) {
        if (merchantId == null) {
            return Collections.emptyMap();
        }
        return merchantAdminSessions.computeIfAbsent(merchantId, ignored -> new ConcurrentHashMap<>());
    }
}
