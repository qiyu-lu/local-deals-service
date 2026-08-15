package com.localdeals.websocket;

import com.localdeals.auth.AdminPermissionCodes;
import com.localdeals.dto.AdminPrincipal;
import com.localdeals.service.AdminSessionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.util.Collections;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static com.localdeals.websocket.AdminWebSocketAuthInterceptor.ADMIN_ACCOUNT_ID_ATTRIBUTE;
import static com.localdeals.websocket.AdminWebSocketAuthInterceptor.ADMIN_MERCHANT_ID_ATTRIBUTE;
import static com.localdeals.websocket.AdminWebSocketAuthInterceptor.ADMIN_SCOPE_TYPE_ATTRIBUTE;
import static com.localdeals.websocket.AdminWebSocketAuthInterceptor.ADMIN_TOKEN_ATTRIBUTE;
import static com.localdeals.websocket.WebSocketAuthInterceptor.CONNECTION_TYPE_ADMIN;
import static com.localdeals.websocket.WebSocketAuthInterceptor.CONNECTION_TYPE_ATTRIBUTE;
import static com.localdeals.websocket.WebSocketAuthInterceptor.CONNECTION_TYPE_USER;
import static com.localdeals.websocket.WebSocketAuthInterceptor.TOKEN_ATTRIBUTE;
import static com.localdeals.websocket.WebSocketAuthInterceptor.USER_ID_ATTRIBUTE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WebSocketSessionIsolationTest {

    private WebSocketAuthInterceptor userAuthInterceptor;
    private AdminSessionService adminSessionService;
    private SeckillWebSocketHandler handler;

    @BeforeEach
    void setUp() {
        userAuthInterceptor = mock(WebSocketAuthInterceptor.class);
        adminSessionService = mock(AdminSessionService.class);
        when(userAuthInterceptor.isTokenValidForUser("user-token", 11L)).thenReturn(true);
        when(adminSessionService.resolve("platform-token", false)).thenReturn(
                adminPrincipal(1L, null, AdminPrincipal.SCOPE_PLATFORM, true));
        when(adminSessionService.resolve("merchant-a-token", false)).thenReturn(
                adminPrincipal(101L, 10L, AdminPrincipal.SCOPE_MERCHANT, true));
        when(adminSessionService.resolve("merchant-b-token", false)).thenReturn(
                adminPrincipal(102L, 20L, AdminPrincipal.SCOPE_MERCHANT, true));
        handler = new SeckillWebSocketHandler(userAuthInterceptor, adminSessionService);
    }

    @Test
    void merchantBroadcastReachesOnlyTheExactMerchant() throws Exception {
        WebSocketSession userSession = userSession("user-session", 11L, "user-token");
        WebSocketSession platformSession = adminSession(
                "platform-session", 1L, null, AdminPrincipal.SCOPE_PLATFORM, "platform-token");
        WebSocketSession merchantA = adminSession(
                "merchant-a", 101L, 10L, AdminPrincipal.SCOPE_MERCHANT, "merchant-a-token");
        WebSocketSession merchantB = adminSession(
                "merchant-b", 102L, 20L, AdminPrincipal.SCOPE_MERCHANT, "merchant-b-token");
        handler.afterConnectionEstablished(userSession);
        handler.afterConnectionEstablished(platformSession);
        handler.afterConnectionEstablished(merchantA);
        handler.afterConnectionEstablished(merchantB);

        handler.sendToMerchantAdmins(10L, "merchant-a-only");

        verify(merchantA).sendMessage(argThat(message ->
                "merchant-a-only".equals(message.getPayload())));
        verify(merchantB, never()).sendMessage(any(TextMessage.class));
        verify(platformSession, never()).sendMessage(any(TextMessage.class));
        verify(userSession, never()).sendMessage(any(TextMessage.class));
    }

    @Test
    void platformBroadcastNeverLeaksIntoMerchantOrUserRegistries() throws Exception {
        WebSocketSession userSession = userSession("user-session", 11L, "user-token");
        WebSocketSession platformSession = adminSession(
                "platform-session", 1L, null, AdminPrincipal.SCOPE_PLATFORM, "platform-token");
        WebSocketSession merchantSession = adminSession(
                "merchant-a", 101L, 10L, AdminPrincipal.SCOPE_MERCHANT, "merchant-a-token");
        handler.afterConnectionEstablished(userSession);
        handler.afterConnectionEstablished(platformSession);
        handler.afterConnectionEstablished(merchantSession);

        handler.sendToPlatformAdmins("platform-only");

        verify(platformSession).sendMessage(argThat(message ->
                "platform-only".equals(message.getPayload())));
        verify(merchantSession, never()).sendMessage(any(TextMessage.class));
        verify(userSession, never()).sendMessage(any(TextMessage.class));
    }

    @Test
    void userReceiptNeverUsesEitherAdminRegistry() throws Exception {
        WebSocketSession userSession = userSession("user-session", 11L, "user-token");
        WebSocketSession platformSession = adminSession(
                "platform-session", 1L, null, AdminPrincipal.SCOPE_PLATFORM, "platform-token");
        WebSocketSession merchantSession = adminSession(
                "merchant-a", 101L, 10L, AdminPrincipal.SCOPE_MERCHANT, "merchant-a-token");
        handler.afterConnectionEstablished(userSession);
        handler.afterConnectionEstablished(platformSession);
        handler.afterConnectionEstablished(merchantSession);

        handler.sendToUser(11L, "user-only");

        verify(userSession).sendMessage(argThat(message -> "user-only".equals(message.getPayload())));
        verify(platformSession, never()).sendMessage(any(TextMessage.class));
        verify(merchantSession, never()).sendMessage(any(TextMessage.class));
    }

    @Test
    void revokedAdminTokenClosesAndRemovesSessionBeforeBroadcast() throws Exception {
        WebSocketSession adminSession = adminSession(
                "revoked-session", 77L, 10L, AdminPrincipal.SCOPE_MERCHANT, "revoked-token");
        handler.afterConnectionEstablished(adminSession);

        handler.sendToMerchantAdmins(10L, "merchant-only");

        verify(adminSession, never()).sendMessage(any(TextMessage.class));
        verify(adminSession).close(CloseStatus.POLICY_VIOLATION);
        assertThat(handler.getMerchantAdminSessionsForTest(10L)).isEmpty();
    }

    @Test
    void removedRealtimePermissionClosesSessionBeforeBroadcast() throws Exception {
        when(adminSessionService.resolve("no-permission-token", false)).thenReturn(
                adminPrincipal(101L, 10L, AdminPrincipal.SCOPE_MERCHANT, false));
        WebSocketSession adminSession = adminSession(
                "no-permission", 101L, 10L, AdminPrincipal.SCOPE_MERCHANT,
                "no-permission-token");
        handler.afterConnectionEstablished(adminSession);

        handler.sendToMerchantAdmins(10L, "merchant-only");

        verify(adminSession, never()).sendMessage(any(TextMessage.class));
        verify(adminSession).close(CloseStatus.POLICY_VIOLATION);
        assertThat(handler.getMerchantAdminSessionsForTest(10L)).isEmpty();
    }

    @Test
    void changedMerchantScopeClosesSessionInsteadOfCrossTenantDelivery() throws Exception {
        when(adminSessionService.resolve("moved-account-token", false)).thenReturn(
                adminPrincipal(101L, 20L, AdminPrincipal.SCOPE_MERCHANT, true));
        WebSocketSession adminSession = adminSession(
                "moved-account", 101L, 10L, AdminPrincipal.SCOPE_MERCHANT,
                "moved-account-token");
        handler.afterConnectionEstablished(adminSession);

        handler.sendToMerchantAdmins(10L, "merchant-only");

        verify(adminSession, never()).sendMessage(any(TextMessage.class));
        verify(adminSession).close(CloseStatus.POLICY_VIOLATION);
        assertThat(handler.getMerchantAdminSessionsForTest(10L)).isEmpty();
    }

    @Test
    void revokedUserTokenBehaviorIsUnchanged() throws Exception {
        WebSocketSession userSession = userSession(
                "revoked-user", 11L, "revoked-user-token");
        handler.afterConnectionEstablished(userSession);

        boolean sent = handler.sendToUser(11L, "user-only");

        assertThat(sent).isFalse();
        verify(userSession, never()).sendMessage(any(TextMessage.class));
        verify(userSession).close(CloseStatus.POLICY_VIOLATION);
        assertThat(handler.getUserSessionsForTest()).isEmpty();
    }

    @Test
    void concurrentAdminBurstsNeverWriteTheNativeSessionInParallel() throws Exception {
        WebSocketSession adminSession = adminSession(
                "merchant-burst", 101L, 10L, AdminPrincipal.SCOPE_MERCHANT,
                "merchant-a-token");
        AtomicInteger activeWrites = new AtomicInteger();
        AtomicInteger maxActiveWrites = new AtomicInteger();
        doAnswer(invocation -> {
            int active = activeWrites.incrementAndGet();
            maxActiveWrites.accumulateAndGet(active, Math::max);
            try {
                Thread.sleep(5L);
            } finally {
                activeWrites.decrementAndGet();
            }
            return null;
        }).when(adminSession).sendMessage(any(TextMessage.class));
        handler.afterConnectionEstablished(adminSession);
        ExecutorService executor = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> sends = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            final int event = i;
            sends.add(executor.submit(() -> {
                start.await();
                handler.sendToMerchantAdmins(10L, "event-" + event);
                return null;
            }));
        }

        start.countDown();
        for (Future<?> send : sends) {
            send.get(5, TimeUnit.SECONDS);
        }
        executor.shutdownNow();

        assertThat(maxActiveWrites.get()).isEqualTo(1);
        verify(adminSession, times(20)).sendMessage(any(TextMessage.class));
    }

    @Test
    void failedAdminSendClosesAndRemovesTheBrokenSession() throws Exception {
        WebSocketSession adminSession = adminSession(
                "broken-session", 101L, 10L, AdminPrincipal.SCOPE_MERCHANT,
                "merchant-a-token");
        doThrow(new java.io.IOException("broken connection"))
                .when(adminSession).sendMessage(any(TextMessage.class));
        handler.afterConnectionEstablished(adminSession);

        handler.sendToMerchantAdmins(10L, "event");

        verify(adminSession).close(CloseStatus.SERVER_ERROR);
        assertThat(handler.getMerchantAdminSessionsForTest(10L)).isEmpty();
    }

    private WebSocketSession userSession(String sessionId, Long userId, String token) {
        WebSocketSession session = mock(WebSocketSession.class);
        Map<String, Object> attributes = new HashMap<>();
        attributes.put(USER_ID_ATTRIBUTE, userId);
        attributes.put(CONNECTION_TYPE_ATTRIBUTE, CONNECTION_TYPE_USER);
        attributes.put(TOKEN_ATTRIBUTE, token);
        when(session.getId()).thenReturn(sessionId);
        when(session.getAttributes()).thenReturn(attributes);
        when(session.isOpen()).thenReturn(true);
        return session;
    }

    private WebSocketSession adminSession(String sessionId, Long accountId, Long merchantId,
            String scopeType, String token) {
        WebSocketSession session = mock(WebSocketSession.class);
        Map<String, Object> attributes = new HashMap<>();
        attributes.put(ADMIN_ACCOUNT_ID_ATTRIBUTE, accountId);
        attributes.put(ADMIN_MERCHANT_ID_ATTRIBUTE, merchantId);
        attributes.put(ADMIN_SCOPE_TYPE_ATTRIBUTE, scopeType);
        attributes.put(ADMIN_TOKEN_ATTRIBUTE, token);
        attributes.put(CONNECTION_TYPE_ATTRIBUTE, CONNECTION_TYPE_ADMIN);
        when(session.getId()).thenReturn(sessionId);
        when(session.getAttributes()).thenReturn(attributes);
        when(session.isOpen()).thenReturn(true);
        return session;
    }

    private AdminPrincipal adminPrincipal(Long accountId, Long merchantId, String scopeType,
            boolean realtimePermission) {
        AdminPrincipal principal = new AdminPrincipal();
        principal.setAccountId(accountId);
        principal.setMerchantId(merchantId);
        principal.setScopeType(scopeType);
        principal.setPermissions(realtimePermission
                ? Collections.singleton(AdminPermissionCodes.ORDER_REALTIME)
                : Collections.emptySet());
        return principal;
    }
}
