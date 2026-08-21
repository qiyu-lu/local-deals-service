package com.localdeals.interctptor;

import com.localdeals.exception.ApiErrorCodes;
import com.localdeals.exception.ApiStatusException;
import com.localdeals.utils.UserHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class RefreshTokenInterceptorTest {

    @AfterEach
    void cleanupUser() {
        UserHolder.removeUser();
    }

    @Test
    void requestWithoutTokenDoesNotTouchRedis() throws Exception {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeader("authorization")).thenReturn(null);

        boolean allowed = new RefreshTokenInterceptor(redisTemplate)
                .preHandle(request, mock(HttpServletResponse.class), new Object());

        assertThat(allowed).isTrue();
        verifyNoInteractions(redisTemplate);
    }

    @Test
    void redisFailureMapsToStableAuthUnavailable() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        HashOperations<String, Object, Object> hashOperations = mock(HashOperations.class);
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeader("authorization")).thenReturn("isolated-token");
        when(redisTemplate.opsForHash()).thenReturn(hashOperations);
        when(hashOperations.entries("login:token:isolated-token"))
                .thenThrow(new IllegalStateException("redis unavailable"));

        assertThatThrownBy(() -> new RefreshTokenInterceptor(redisTemplate)
                .preHandle(request, mock(HttpServletResponse.class), new Object()))
                .isInstanceOfSatisfying(ApiStatusException.class, error -> {
                    assertThat(error.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                    assertThat(error.getCode()).isEqualTo(ApiErrorCodes.AUTH_STATE_UNAVAILABLE);
                });
        assertThat(UserHolder.getUser()).isNull();
    }

    @Test
    void unknownTokenStillPassesAsAnonymous() throws Exception {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        HashOperations<String, Object, Object> hashOperations = mock(HashOperations.class);
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeader("authorization")).thenReturn("unknown-token");
        when(redisTemplate.opsForHash()).thenReturn(hashOperations);
        when(hashOperations.entries("login:token:unknown-token")).thenReturn(Collections.emptyMap());

        boolean allowed = new RefreshTokenInterceptor(redisTemplate)
                .preHandle(request, mock(HttpServletResponse.class), new Object());

        assertThat(allowed).isTrue();
        assertThat(UserHolder.getUser()).isNull();
    }
}
