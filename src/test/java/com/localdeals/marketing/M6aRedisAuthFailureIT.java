package com.localdeals.marketing;

import com.localdeals.interctptor.LoginInterceptor;
import com.localdeals.interctptor.RefreshTokenInterceptor;
import com.localdeals.utils.RedisConstants;
import com.localdeals.utils.UserHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfEnvironmentVariable(named = "M6A_ISOLATED", matches = "true")
class M6aRedisAuthFailureIT {
    private LettuceConnectionFactory connectionFactory;

    @AfterEach
    void clearUser() {
        UserHolder.removeUser();
        if (connectionFactory != null) connectionFactory.destroy();
    }

    @Test
    void unknownTokenIsRejectedByLoginInterceptorWithoutRedisSideEffect() throws Exception {
        String runId = required("M6A_RUN_ID");
        int port = Integer.parseInt(required("M6A_REDIS_PORT"));
        connectionFactory = new LettuceConnectionFactory(
                new RedisStandaloneConfiguration("127.0.0.1", port));
        connectionFactory.afterPropertiesSet();
        StringRedisTemplate redisTemplate = new StringRedisTemplate(connectionFactory);
        redisTemplate.afterPropertiesSet();

        String token = "m6a-unknown-token-" + runId;
        String key = RedisConstants.LOGIN_USER_KEY + token;
        redisTemplate.delete(key);
        assertThat(redisTemplate.opsForValue().get("m6a:sentinel:" + runId)).isEqualTo(runId);
        Long before = redisTemplate.getConnectionFactory().getConnection().dbSize();

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/voucher-grants/mine");
        request.addHeader("authorization", token);
        MockHttpServletResponse response = new MockHttpServletResponse();
        assertThat(new RefreshTokenInterceptor(redisTemplate)
                .preHandle(request, response, new Object())).isTrue();
        assertThat(new LoginInterceptor(Collections.emptyList(), Collections.emptyList())
                .preHandle(request, response, new Object())).isFalse();
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(UserHolder.getUser()).isNull();
        assertThat(redisTemplate.getConnectionFactory().getConnection().dbSize()).isEqualTo(before);
        assertThat(redisTemplate.hasKey(key)).isFalse();
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalStateException(name + " must identify the isolated M6A Redis");
        }
        return value;
    }
}
