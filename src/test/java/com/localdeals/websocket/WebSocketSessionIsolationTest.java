package com.localdeals.websocket;

import com.localdeals.config.AdminProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.util.HashMap;
import java.util.Map;

import static com.localdeals.websocket.WebSocketAuthInterceptor.CONNECTION_TYPE_ADMIN;
import static com.localdeals.websocket.WebSocketAuthInterceptor.CONNECTION_TYPE_ATTRIBUTE;
import static com.localdeals.websocket.WebSocketAuthInterceptor.CONNECTION_TYPE_USER;
import static com.localdeals.websocket.WebSocketAuthInterceptor.TOKEN_ATTRIBUTE;
import static com.localdeals.websocket.WebSocketAuthInterceptor.USER_ID_ATTRIBUTE;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WebSocketSessionIsolationTest {

    private WebSocketAuthInterceptor authInterceptor;
    private AdminProperties adminProperties;
    private SeckillWebSocketHandler handler;

    @BeforeEach
    void setUp() {
        authInterceptor = mock(WebSocketAuthInterceptor.class);
        adminProperties = new AdminProperties();
        adminProperties.setUserIds("99");
        when(authInterceptor.isTokenValidForUser("admin-token", 99L)).thenReturn(true);
        when(authInterceptor.isTokenValidForUser("user-token", 11L)).thenReturn(true);
        handler = new SeckillWebSocketHandler(authInterceptor, adminProperties);
    }

    @Test
    void adminBroadcastNeverReachesOrdinaryUserSession() throws Exception {
        WebSocketSession userSession = session("user-session", 11L, CONNECTION_TYPE_USER, "user-token");
        WebSocketSession adminSession = session("admin-session", 99L, CONNECTION_TYPE_ADMIN, "admin-token");

        handler.afterConnectionEstablished(userSession);
        handler.afterConnectionEstablished(adminSession);

        handler.sendToAdmins("admin-only");

        verify(adminSession).sendMessage(argThat(message -> "admin-only".equals(message.getPayload())));
        verify(userSession, never()).sendMessage(any(TextMessage.class));
    }

    @Test
    void userReceiptNeverUsesAdminSessionRegistry() throws Exception {
        WebSocketSession userSession = session("user-session", 11L, CONNECTION_TYPE_USER, "user-token");
        WebSocketSession adminSession = session("admin-session", 99L, CONNECTION_TYPE_ADMIN, "admin-token");

        handler.afterConnectionEstablished(userSession);
        handler.afterConnectionEstablished(adminSession);

        handler.sendToUser(11L, "user-only");

        verify(userSession).sendMessage(argThat(message -> "user-only".equals(message.getPayload())));
        verify(adminSession, never()).sendMessage(any(TextMessage.class));
    }

    @Test
    void revokedAdminTokenClosesAndRemovesSessionBeforeBroadcast() throws Exception {
        WebSocketSession adminSession = session(
                "revoked-session", 99L, CONNECTION_TYPE_ADMIN, "revoked-token");
        handler.afterConnectionEstablished(adminSession);

        handler.sendToAdmins("admin-only");

        verify(adminSession, never()).sendMessage(any(TextMessage.class));
        verify(adminSession).close(CloseStatus.POLICY_VIOLATION);
        org.assertj.core.api.Assertions.assertThat(handler.getAdminSessionsForTest()).isEmpty();
    }

    @Test
    void removedAdminAllowlistEntryClosesSessionBeforeBroadcast() throws Exception {
        WebSocketSession adminSession = session(
                "removed-admin", 99L, CONNECTION_TYPE_ADMIN, "admin-token");
        handler.afterConnectionEstablished(adminSession);
        adminProperties.setUserIds("");

        handler.sendToAdmins("admin-only");

        verify(adminSession, never()).sendMessage(any(TextMessage.class));
        verify(adminSession).close(CloseStatus.POLICY_VIOLATION);
        org.assertj.core.api.Assertions.assertThat(handler.getAdminSessionsForTest()).isEmpty();
    }

    @Test
    void revokedUserTokenClosesAndRemovesSessionBeforeReceipt() throws Exception {
        WebSocketSession userSession = session(
                "revoked-user", 11L, CONNECTION_TYPE_USER, "revoked-user-token");
        handler.afterConnectionEstablished(userSession);

        boolean sent = handler.sendToUser(11L, "user-only");

        org.assertj.core.api.Assertions.assertThat(sent).isFalse();
        verify(userSession, never()).sendMessage(any(TextMessage.class));
        verify(userSession).close(CloseStatus.POLICY_VIOLATION);
        org.assertj.core.api.Assertions.assertThat(handler.getUserSessionsForTest()).isEmpty();
    }

    private WebSocketSession session(String sessionId, Long userId, String connectionType, String token) {
        WebSocketSession session = mock(WebSocketSession.class);
        Map<String, Object> attributes = new HashMap<>();
        attributes.put(USER_ID_ATTRIBUTE, userId);
        attributes.put(CONNECTION_TYPE_ATTRIBUTE, connectionType);
        attributes.put(TOKEN_ATTRIBUTE, token);
        when(session.getId()).thenReturn(sessionId);
        when(session.getAttributes()).thenReturn(attributes);
        when(session.isOpen()).thenReturn(true);
        return session;
    }
}
