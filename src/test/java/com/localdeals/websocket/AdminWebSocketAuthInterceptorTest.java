package com.localdeals.websocket;

import com.localdeals.config.AdminProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.socket.WebSocketHandler;

import java.util.HashMap;
import java.util.Map;

import static com.localdeals.websocket.WebSocketAuthInterceptor.CONNECTION_TYPE_ADMIN;
import static com.localdeals.websocket.WebSocketAuthInterceptor.CONNECTION_TYPE_ATTRIBUTE;
import static com.localdeals.websocket.WebSocketAuthInterceptor.TOKEN_ATTRIBUTE;
import static com.localdeals.websocket.WebSocketAuthInterceptor.USER_ID_ATTRIBUTE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AdminWebSocketAuthInterceptorTest {

    private HashOperations<String, Object, Object> hashOperations;
    private AdminProperties properties;
    private WebSocketHandler handler;
    private ServerHttpResponse response;
    private WebSocketAuthInterceptor userAuth;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        hashOperations = mock(HashOperations.class);
        when(redisTemplate.opsForHash()).thenReturn(hashOperations);
        properties = new AdminProperties();
        handler = mock(WebSocketHandler.class);
        response = mock(ServerHttpResponse.class);

        userAuth = new WebSocketAuthInterceptor(redisTemplate);
        interceptor = new AdminWebSocketAuthInterceptor(userAuth, properties);
    }

    private AdminWebSocketAuthInterceptor interceptor;

    @Test
    void emptyAllowlistRejectsAuthenticatedUser() {
        when(hashOperations.entries(anyString())).thenReturn(redisUser(7L));
        Map<String, Object> attributes = new HashMap<>();

        boolean accepted = interceptor.beforeHandshake(request("token-7"), response, handler, attributes);

        assertThat(accepted).isFalse();
        assertThat(attributes).doesNotContainKeys(USER_ID_ATTRIBUTE, TOKEN_ATTRIBUTE, CONNECTION_TYPE_ATTRIBUTE);
    }

    @Test
    void allowlistedUserIsMarkedAsAdmin() {
        properties.setUserIds("7, 9");
        when(hashOperations.entries(anyString())).thenReturn(redisUser(7L));
        Map<String, Object> attributes = new HashMap<>();

        boolean accepted = interceptor.beforeHandshake(request("token-7"), response, handler, attributes);

        assertThat(accepted).isTrue();
        assertThat(attributes.get(USER_ID_ATTRIBUTE)).isEqualTo(7L);
        assertThat(attributes.get(TOKEN_ATTRIBUTE)).isEqualTo("token-7");
        assertThat(attributes.get(CONNECTION_TYPE_ATTRIBUTE)).isEqualTo(CONNECTION_TYPE_ADMIN);
    }

    @Test
    void sessionTokenRevalidationRequiresTheSameUser() {
        when(hashOperations.entries(anyString())).thenReturn(redisUser(7L));

        assertThat(userAuth.isTokenValidForUser("token-7", 7L)).isTrue();
        assertThat(userAuth.isTokenValidForUser("token-7", 8L)).isFalse();
    }

    private ServletServerHttpRequest request(String token) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setParameter("token", token);
        return new ServletServerHttpRequest(request);
    }

    private Map<Object, Object> redisUser(Long userId) {
        Map<Object, Object> user = new HashMap<>();
        user.put("id", userId.toString());
        user.put("nickName", "admin");
        return user;
    }
}
