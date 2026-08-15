package com.localdeals.websocket;

import com.localdeals.auth.AdminPermissionCodes;
import com.localdeals.dto.AdminPrincipal;
import com.localdeals.service.AdminSessionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.socket.WebSocketHandler;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static com.localdeals.websocket.AdminWebSocketAuthInterceptor.ADMIN_ACCOUNT_ID_ATTRIBUTE;
import static com.localdeals.websocket.AdminWebSocketAuthInterceptor.ADMIN_MERCHANT_ID_ATTRIBUTE;
import static com.localdeals.websocket.AdminWebSocketAuthInterceptor.ADMIN_SCOPE_TYPE_ATTRIBUTE;
import static com.localdeals.websocket.AdminWebSocketAuthInterceptor.ADMIN_TOKEN_ATTRIBUTE;
import static com.localdeals.websocket.WebSocketAuthInterceptor.CONNECTION_TYPE_ADMIN;
import static com.localdeals.websocket.WebSocketAuthInterceptor.CONNECTION_TYPE_ATTRIBUTE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AdminWebSocketAuthInterceptorTest {

    private AdminSessionService adminSessionService;
    private WebSocketHandler handler;
    private ServerHttpResponse response;
    private AdminWebSocketAuthInterceptor interceptor;

    @BeforeEach
    void setUp() {
        adminSessionService = mock(AdminSessionService.class);
        handler = mock(WebSocketHandler.class);
        response = mock(ServerHttpResponse.class);
        interceptor = new AdminWebSocketAuthInterceptor(adminSessionService);
    }

    @Test
    void missingTicketRejectsHandshakeWithoutConsumingAnything() {
        Map<String, Object> attributes = new HashMap<>();

        boolean accepted = interceptor.beforeHandshake(request(null, "leaked-long-token"),
                response, handler, attributes);

        assertThat(accepted).isFalse();
        assertThat(attributes).isEmpty();
        verify(adminSessionService, never()).consumeWebSocketTicket("leaked-long-token");
        verify(response).setStatusCode(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void expiredOrAlreadyConsumedTicketIsRejected() {
        when(adminSessionService.consumeWebSocketTicket("used-ticket")).thenReturn(null);
        Map<String, Object> attributes = new HashMap<>();

        boolean accepted = interceptor.beforeHandshake(request("used-ticket", null),
                response, handler, attributes);

        assertThat(accepted).isFalse();
        assertThat(attributes).isEmpty();
        verify(response).setStatusCode(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void missingRealtimePermissionConsumesTicketButRejectsHandshake() {
        when(adminSessionService.consumeWebSocketTicket("staff-ticket")).thenReturn("staff-token");
        when(adminSessionService.resolve("staff-token", false)).thenReturn(
                principal(7L, 23L, AdminPrincipal.SCOPE_MERCHANT, false));
        Map<String, Object> attributes = new HashMap<>();

        boolean accepted = interceptor.beforeHandshake(request("staff-ticket", null),
                response, handler, attributes);

        assertThat(accepted).isFalse();
        assertThat(attributes).isEmpty();
        verify(response).setStatusCode(HttpStatus.FORBIDDEN);
    }

    @Test
    void validMerchantTicketBindsExactAccountAndMerchantScope() {
        when(adminSessionService.consumeWebSocketTicket("merchant-ticket"))
                .thenReturn("merchant-token");
        when(adminSessionService.resolve("merchant-token", false)).thenReturn(
                principal(7L, 23L, AdminPrincipal.SCOPE_MERCHANT, true));
        Map<String, Object> attributes = new HashMap<>();

        boolean accepted = interceptor.beforeHandshake(request("merchant-ticket", null),
                response, handler, attributes);

        assertThat(accepted).isTrue();
        assertThat(attributes)
                .containsEntry(ADMIN_ACCOUNT_ID_ATTRIBUTE, 7L)
                .containsEntry(ADMIN_MERCHANT_ID_ATTRIBUTE, 23L)
                .containsEntry(ADMIN_SCOPE_TYPE_ATTRIBUTE, AdminPrincipal.SCOPE_MERCHANT)
                .containsEntry(ADMIN_TOKEN_ATTRIBUTE, "merchant-token")
                .containsEntry(CONNECTION_TYPE_ATTRIBUTE, CONNECTION_TYPE_ADMIN);
    }

    @Test
    void validPlatformTicketDoesNotAcquireMerchantScope() {
        when(adminSessionService.consumeWebSocketTicket("platform-ticket"))
                .thenReturn("platform-token");
        when(adminSessionService.resolve("platform-token", false)).thenReturn(
                principal(1L, null, AdminPrincipal.SCOPE_PLATFORM, true));
        Map<String, Object> attributes = new HashMap<>();

        boolean accepted = interceptor.beforeHandshake(request("platform-ticket", null),
                response, handler, attributes);

        assertThat(accepted).isTrue();
        assertThat(attributes)
                .containsEntry(ADMIN_ACCOUNT_ID_ATTRIBUTE, 1L)
                .containsEntry(ADMIN_SCOPE_TYPE_ATTRIBUTE, AdminPrincipal.SCOPE_PLATFORM)
                .containsEntry(ADMIN_TOKEN_ATTRIBUTE, "platform-token")
                .containsEntry(CONNECTION_TYPE_ATTRIBUTE, CONNECTION_TYPE_ADMIN);
        assertThat(attributes).doesNotContainKey(ADMIN_MERCHANT_ID_ATTRIBUTE);
    }

    private ServletServerHttpRequest request(String ticket, String legacyToken) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        if (ticket != null) {
            request.setParameter("ticket", ticket);
        }
        if (legacyToken != null) {
            request.setParameter("token", legacyToken);
        }
        return new ServletServerHttpRequest(request);
    }

    private AdminPrincipal principal(Long accountId, Long merchantId, String scopeType,
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
