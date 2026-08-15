package com.localdeals.websocket;

import com.localdeals.config.AdminProperties;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.localdeals.utils.RedisConstants.LOGIN_USER_KEY;
import static com.localdeals.websocket.WebSocketAuthInterceptor.CONNECTION_TYPE_ADMIN;
import static com.localdeals.websocket.WebSocketAuthInterceptor.CONNECTION_TYPE_ATTRIBUTE;
import static com.localdeals.websocket.WebSocketAuthInterceptor.TOKEN_ATTRIBUTE;
import static com.localdeals.websocket.WebSocketAuthInterceptor.USER_ID_ATTRIBUTE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = "local-deals.admin.user-ids=98765")
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
    private AdminProperties adminProperties;

    @Test
    void notify_sendsMessageToRegisteredSession() throws Exception {
        Long userId = 12345L;
        String userToken = "ws-user-it-token";
        Long adminUserId = 98765L;
        String adminToken = "ws-admin-it-token";

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
        Map<String, Object> adminAttributes = new HashMap<>();
        adminAttributes.put(USER_ID_ATTRIBUTE, adminUserId);
        adminAttributes.put(TOKEN_ATTRIBUTE, adminToken);
        adminAttributes.put(CONNECTION_TYPE_ATTRIBUTE, CONNECTION_TYPE_ADMIN);
        Mockito.when(adminSession.getAttributes()).thenReturn(adminAttributes);
        stringRedisTemplate.opsForHash().put(
                LOGIN_USER_KEY + adminToken, "id", adminUserId.toString());
        stringRedisTemplate.opsForHash().put(
                LOGIN_USER_KEY + userToken, "id", userId.toString());
        assertThat(webSocketAuthInterceptor.isTokenValidForUser(userToken, userId)).isTrue();
        assertThat(webSocketAuthInterceptor.isTokenValidForUser(adminToken, adminUserId)).isTrue();
        assertThat(adminProperties.isAdminUser(adminUserId)).isTrue();

        // Register isolated user/admin sessions directly into the handler.
        webSocketHandler.getUserSessionsForTest().put(userId, userSession);
        webSocketHandler.getUserSessionsForTest().put(54321L, otherUserSession);
        webSocketHandler.getAdminSessionsForTest().put("admin-session", adminSession);

        try {
            // Send notification via Redis pub/sub; RedisMessageListenerContainer should
            // forward it to webSocketHandler.sendToUser(...) on this same JVM.
            webSocketNotifier.notify(userId, true, 99L, 10L);

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

            // A different ordinary user must never receive the admin broadcast.
            Mockito.verify(otherUserSession, Mockito.after(300).never())
                    .sendMessage(ArgumentMatchers.any());

            // Capture the message and verify content
            ArgumentCaptor<TextMessage> captor = ArgumentCaptor.forClass(TextMessage.class);
            Mockito.verify(userSession).sendMessage(captor.capture());
            String payload = captor.getValue().getPayload();
            assertThat(payload).contains("\"success\":true");
            assertThat(payload).contains("\"orderId\":99");
            assertThat(payload).contains("\"voucherId\":10");
            assertThat(payload).contains("SECKILL_RESULT");
        } finally {
            webSocketHandler.getUserSessionsForTest().remove(userId);
            webSocketHandler.getUserSessionsForTest().remove(54321L);
            webSocketHandler.getAdminSessionsForTest().remove("admin-session");
            stringRedisTemplate.delete(LOGIN_USER_KEY + adminToken);
            stringRedisTemplate.delete(LOGIN_USER_KEY + userToken);
        }
    }
}
