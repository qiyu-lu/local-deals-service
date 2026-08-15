package com.localdeals.websocket;

import cn.hutool.core.util.StrUtil;
import com.localdeals.auth.AdminPermissionCodes;
import com.localdeals.dto.AdminPrincipal;
import com.localdeals.service.AdminSessionService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.util.Map;

import static com.localdeals.websocket.WebSocketAuthInterceptor.CONNECTION_TYPE_ADMIN;
import static com.localdeals.websocket.WebSocketAuthInterceptor.CONNECTION_TYPE_ATTRIBUTE;

/**
 * Authenticates a privileged WebSocket handshake with a short-lived, single-use ticket.
 *
 * <p>The long-lived admin bearer token never appears in the WebSocket URL. The ticket is
 * atomically consumed by {@link AdminSessionService}; the resolved token and scope are then
 * bound to the session so every privileged delivery can revalidate the account and its
 * {@code order:realtime} permission.</p>
 */
@Slf4j
@Component
public class AdminWebSocketAuthInterceptor implements HandshakeInterceptor {

    public static final String ADMIN_ACCOUNT_ID_ATTRIBUTE = "adminAccountId";
    public static final String ADMIN_MERCHANT_ID_ATTRIBUTE = "adminMerchantId";
    public static final String ADMIN_SCOPE_TYPE_ATTRIBUTE = "adminScopeType";
    public static final String ADMIN_TOKEN_ATTRIBUTE = "adminAuthToken";

    private final AdminSessionService adminSessionService;

    public AdminWebSocketAuthInterceptor(AdminSessionService adminSessionService) {
        this.adminSessionService = adminSessionService;
    }

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
            WebSocketHandler wsHandler, Map<String, Object> attributes) {
        if (!(request instanceof ServletServerHttpRequest)) {
            return reject(response, HttpStatus.UNAUTHORIZED, "unsupported request type");
        }

        String ticket = ((ServletServerHttpRequest) request).getServletRequest().getParameter("ticket");
        if (StrUtil.isBlank(ticket)) {
            return reject(response, HttpStatus.UNAUTHORIZED, "missing ticket");
        }

        final String token;
        final AdminPrincipal principal;
        try {
            token = adminSessionService.consumeWebSocketTicket(ticket);
            principal = StrUtil.isBlank(token) ? null : adminSessionService.resolve(token, false);
        } catch (RuntimeException e) {
            log.warn("Admin WebSocket ticket validation failed", e);
            return reject(response, HttpStatus.UNAUTHORIZED, "ticket validation failed");
        }

        if (principal == null) {
            return reject(response, HttpStatus.UNAUTHORIZED, "invalid or expired ticket");
        }
        if (!principal.hasPermission(AdminPermissionCodes.ORDER_REALTIME)) {
            return reject(response, HttpStatus.FORBIDDEN, "missing realtime permission");
        }
        if (!hasValidScope(principal)) {
            return reject(response, HttpStatus.FORBIDDEN, "invalid account scope");
        }

        attributes.put(ADMIN_ACCOUNT_ID_ATTRIBUTE, principal.getAccountId());
        if (principal.getMerchantId() != null) {
            attributes.put(ADMIN_MERCHANT_ID_ATTRIBUTE, principal.getMerchantId());
        }
        attributes.put(ADMIN_SCOPE_TYPE_ATTRIBUTE, principal.getScopeType());
        attributes.put(ADMIN_TOKEN_ATTRIBUTE, token);
        attributes.put(CONNECTION_TYPE_ATTRIBUTE, CONNECTION_TYPE_ADMIN);
        return true;
    }

    private boolean hasValidScope(AdminPrincipal principal) {
        if (principal.getAccountId() == null) {
            return false;
        }
        if (principal.isPlatform()) {
            return principal.getMerchantId() == null;
        }
        return AdminPrincipal.SCOPE_MERCHANT.equals(principal.getScopeType()) &&
                principal.getMerchantId() != null;
    }

    private boolean reject(ServerHttpResponse response, HttpStatus status, String reason) {
        response.setStatusCode(status);
        log.debug("Admin WebSocket handshake rejected: {}", reason);
        return false;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
            WebSocketHandler wsHandler, Exception exception) {
        // no-op
    }
}
