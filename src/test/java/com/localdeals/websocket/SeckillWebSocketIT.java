package com.localdeals.websocket;

import com.localdeals.auth.AdminPermissionCodes;
import com.localdeals.dto.AdminPrincipal;
import com.localdeals.service.AdminSessionService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.localdeals.utils.RedisConstants.LOGIN_USER_KEY;
import static com.localdeals.websocket.AdminWebSocketAuthInterceptor.ADMIN_ACCOUNT_ID_ATTRIBUTE;
import static com.localdeals.websocket.AdminWebSocketAuthInterceptor.ADMIN_MERCHANT_ID_ATTRIBUTE;
import static com.localdeals.websocket.AdminWebSocketAuthInterceptor.ADMIN_SCOPE_TYPE_ATTRIBUTE;
import static com.localdeals.websocket.AdminWebSocketAuthInterceptor.ADMIN_TOKEN_ATTRIBUTE;
import static com.localdeals.websocket.WebSocketAuthInterceptor.CONNECTION_TYPE_ADMIN;
import static com.localdeals.websocket.WebSocketAuthInterceptor.CONNECTION_TYPE_ATTRIBUTE;
import static com.localdeals.websocket.WebSocketAuthInterceptor.TOKEN_ATTRIBUTE;
import static com.localdeals.websocket.WebSocketAuthInterceptor.USER_ID_ATTRIBUTE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@SpringBootTest
@ActiveProfiles("test")
class SeckillWebSocketIT {

    @Autowired
    private SeckillWebSocketHandler webSocketHandler;

    @Autowired
    private WebSocketNotifier webSocketNotifier;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private WebSocketAuthInterceptor webSocketAuthInterceptor;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockBean
    private AdminSessionService adminSessionService;

    @Test
    void notify_sendsMessageToRegisteredSession() throws Exception {
        Long userId = 12345L;
        String userToken = "ws-user-it-token";
        Long adminAccountId = 98765L;
        String adminToken = "ws-admin-it-token";
        Long merchantId = jdbcTemplate.queryForObject(
                "SELECT s.merchant_id FROM tb_voucher v " +
                        "JOIN tb_shop s ON s.id = v.shop_id WHERE v.id = 1",
                Long.class);
        Long merchantAccountId = 98766L;
        String merchantToken = "ws-merchant-it-token";

        // Create mock session
        WebSocketSession userSession = Mockito.mock(WebSocketSession.class);
        Mockito.when(userSession.isOpen()).thenReturn(true);
        Map<String, Object> userAttributes = new HashMap<>();
        userAttributes.put(USER_ID_ATTRIBUTE, userId);
        userAttributes.put(TOKEN_ATTRIBUTE, userToken);
        Mockito.when(userSession.getAttributes()).thenReturn(userAttributes);
        WebSocketSession otherUserSession = Mockito.mock(WebSocketSession.class);
        Mockito.when(otherUserSession.isOpen()).thenReturn(true);
        WebSocketSession adminSession = Mockito.mock(WebSocketSession.class);
        Mockito.when(adminSession.isOpen()).thenReturn(true);
        Mockito.when(adminSession.getId()).thenReturn("platform-admin-session");
        Map<String, Object> adminAttributes = new HashMap<>();
        adminAttributes.put(ADMIN_ACCOUNT_ID_ATTRIBUTE, adminAccountId);
        adminAttributes.put(ADMIN_MERCHANT_ID_ATTRIBUTE, null);
        adminAttributes.put(ADMIN_SCOPE_TYPE_ATTRIBUTE, AdminPrincipal.SCOPE_PLATFORM);
        adminAttributes.put(ADMIN_TOKEN_ATTRIBUTE, adminToken);
        adminAttributes.put(CONNECTION_TYPE_ATTRIBUTE, CONNECTION_TYPE_ADMIN);
        Mockito.when(adminSession.getAttributes()).thenReturn(adminAttributes);
        AdminPrincipal adminPrincipal = new AdminPrincipal();
        adminPrincipal.setAccountId(adminAccountId);
        adminPrincipal.setScopeType(AdminPrincipal.SCOPE_PLATFORM);
        adminPrincipal.setPermissions(Collections.singleton(AdminPermissionCodes.ORDER_REALTIME));
        Mockito.when(adminSessionService.resolve(adminToken, false)).thenReturn(adminPrincipal);
        WebSocketSession merchantSession = Mockito.mock(WebSocketSession.class);
        Mockito.when(merchantSession.isOpen()).thenReturn(true);
        Mockito.when(merchantSession.getId()).thenReturn("merchant-admin-session");
        Map<String, Object> merchantAttributes = new HashMap<>();
        merchantAttributes.put(ADMIN_ACCOUNT_ID_ATTRIBUTE, merchantAccountId);
        merchantAttributes.put(ADMIN_MERCHANT_ID_ATTRIBUTE, merchantId);
        merchantAttributes.put(ADMIN_SCOPE_TYPE_ATTRIBUTE, AdminPrincipal.SCOPE_MERCHANT);
        merchantAttributes.put(ADMIN_TOKEN_ATTRIBUTE, merchantToken);
        merchantAttributes.put(CONNECTION_TYPE_ATTRIBUTE, CONNECTION_TYPE_ADMIN);
        Mockito.when(merchantSession.getAttributes()).thenReturn(merchantAttributes);
        AdminPrincipal merchantPrincipal = new AdminPrincipal();
        merchantPrincipal.setAccountId(merchantAccountId);
        merchantPrincipal.setMerchantId(merchantId);
        merchantPrincipal.setScopeType(AdminPrincipal.SCOPE_MERCHANT);
        merchantPrincipal.setPermissions(Collections.singleton(AdminPermissionCodes.ORDER_REALTIME));
        Mockito.when(adminSessionService.resolve(merchantToken, false)).thenReturn(merchantPrincipal);
        stringRedisTemplate.opsForHash().put(
                LOGIN_USER_KEY + userToken, "id", userId.toString());
        assertThat(webSocketAuthInterceptor.isTokenValidForUser(userToken, userId)).isTrue();

        // Register isolated user/admin sessions directly into the handler.
        webSocketHandler.getUserSessionsForTest().put(userId, userSession);
        webSocketHandler.getUserSessionsForTest().put(54321L, otherUserSession);
        webSocketHandler.afterConnectionEstablished(adminSession);
        webSocketHandler.afterConnectionEstablished(merchantSession);

        try {
            // Send notification via Redis pub/sub; RedisMessageListenerContainer should
            // forward it to webSocketHandler.sendToUser(...) on this same JVM.
            webSocketNotifier.notify(userId, true, 99L, 1L);

            // Wait for Redis pub/sub delivery
            await()
                    .atMost(3, TimeUnit.SECONDS)
                    .untilAsserted(() ->
                            Mockito.verify(userSession, Mockito.times(1))
                                    .sendMessage(ArgumentMatchers.any()));

            await()
                    .atMost(3, TimeUnit.SECONDS)
                    .untilAsserted(() ->
                            Mockito.verify(adminSession, Mockito.times(1))
                                    .sendMessage(ArgumentMatchers.any()));

            await()
                    .atMost(3, TimeUnit.SECONDS)
                    .untilAsserted(() ->
                            Mockito.verify(merchantSession, Mockito.times(1))
                                    .sendMessage(ArgumentMatchers.any()));

            // A different ordinary user must never receive the admin broadcast.
            Mockito.verify(otherUserSession, Mockito.after(300).never())
                    .sendMessage(ArgumentMatchers.any());

            // Capture the message and verify content
            ArgumentCaptor<TextMessage> captor = ArgumentCaptor.forClass(TextMessage.class);
            Mockito.verify(userSession).sendMessage(captor.capture());
            String payload = captor.getValue().getPayload();
            assertThat(payload).contains("\"success\":true");
            assertThat(payload).contains("\"orderId\":\"99\"");
            assertThat(payload).contains("\"voucherId\":1");
            assertThat(payload).contains("SECKILL_RESULT");
        } finally {
            webSocketHandler.getUserSessionsForTest().remove(userId);
            webSocketHandler.getUserSessionsForTest().remove(54321L);
            webSocketHandler.getPlatformAdminSessionsForTest().remove("platform-admin-session");
            webSocketHandler.getMerchantAdminSessionsForTest(merchantId)
                    .remove("merchant-admin-session");
            stringRedisTemplate.delete(LOGIN_USER_KEY + userToken);
        }
    }
}
