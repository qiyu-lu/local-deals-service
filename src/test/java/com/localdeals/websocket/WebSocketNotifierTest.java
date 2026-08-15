package com.localdeals.websocket;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class WebSocketNotifierTest {

    @Test
    void serializesLargeOrderIdAsAnExactJsonString() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        WebSocketNotifier notifier = new WebSocketNotifier();
        ReflectionTestUtils.setField(notifier, "stringRedisTemplate", redisTemplate);
        long orderId = 90071992547409931L;

        notifier.notify(7L, true, orderId, 17L);

        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(redisTemplate).convertAndSend(
                eq(WebSocketNotifier.CHANNEL_PREFIX + 7L), payload.capture());
        assertThat(payload.getValue())
                .contains("\"orderId\":\"90071992547409931\"")
                .contains("\"success\":true");
    }
}
