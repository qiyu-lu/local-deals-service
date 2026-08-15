package com.localdeals.websocket;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WebSocketNotifierTest {

    @Test
    void serializesExactOrderIdAndPublishesOnlyToUserPlatformAndOwningMerchant() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        WebSocketNotifier notifier = notifier(redisTemplate, jdbcTemplate);
        long orderId = 90071992547409931L;
        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class), eq(17L)))
                .thenReturn(42L);

        notifier.notify(7L, true, orderId, 17L);

        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(redisTemplate).convertAndSend(
                eq(WebSocketNotifier.CHANNEL_PREFIX + 7L), payload.capture());
        verify(redisTemplate).convertAndSend(
                WebSocketNotifier.ADMIN_PLATFORM_CHANNEL, payload.getValue());
        verify(redisTemplate).convertAndSend(
                WebSocketNotifier.ADMIN_MERCHANT_CHANNEL_PREFIX + 42L, payload.getValue());
        assertThat(payload.getValue())
                .contains("\"orderId\":\"90071992547409931\"")
                .contains("\"success\":true");
    }

    @Test
    void merchantLookupFailureNeverDegradesToABroadMerchantBroadcast() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        WebSocketNotifier notifier = notifier(redisTemplate, jdbcTemplate);
        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class), eq(17L)))
                .thenThrow(new DataAccessResourceFailureException("db unavailable"));

        notifier.notify(7L, false, 99L, 17L);

        verify(redisTemplate).convertAndSend(
                eq(WebSocketNotifier.CHANNEL_PREFIX + 7L), anyString());
        verify(redisTemplate).convertAndSend(
                eq(WebSocketNotifier.ADMIN_PLATFORM_CHANNEL), anyString());
        verify(redisTemplate, never()).convertAndSend(
                startsWith(WebSocketNotifier.ADMIN_MERCHANT_CHANNEL_PREFIX), anyString());
    }

    private WebSocketNotifier notifier(StringRedisTemplate redisTemplate, JdbcTemplate jdbcTemplate) {
        WebSocketNotifier notifier = new WebSocketNotifier();
        ReflectionTestUtils.setField(notifier, "stringRedisTemplate", redisTemplate);
        ReflectionTestUtils.setField(notifier, "jdbcTemplate", jdbcTemplate);
        return notifier;
    }
}
