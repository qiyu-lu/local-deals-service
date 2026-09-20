package com.localdeals.platform.websocket;

import com.localdeals.trade.mq.SeckillOrderMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atMostOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A batch of persisted orders announces itself in one Redis round trip. Three publishes per order
 * on the consume thread is exactly the kind of per-order work M4 exists to remove.
 */
class WebSocketNotifierBatchTest {

    private StringRedisTemplate redis;
    private JdbcTemplate jdbc;
    private WebSocketNotifier notifier;

    @BeforeEach
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        jdbc = mock(JdbcTemplate.class);
        notifier = new WebSocketNotifier();
        ReflectionTestUtils.setField(notifier, "stringRedisTemplate", redis);
        ReflectionTestUtils.setField(notifier, "jdbcTemplate", jdbc);
    }

    @Test
    void oneRoundTripAnnouncesTheWholeBatch() {
        when(jdbc.queryForObject(any(String.class), eq(Long.class), any(Object[].class)))
                .thenReturn(42L);

        notifier.notifySeckillBatch(Arrays.asList(
                new SeckillOrderMessage(7L, 101L, 9001L),
                new SeckillOrderMessage(7L, 102L, 9002L)));

        verify(redis).executePipelined(any(SessionCallback.class));
        verify(redis, org.mockito.Mockito.never()).convertAndSend(any(), any());
    }

    @Test
    void theMerchantOfAVoucherIsLookedUpOnceNotOncePerOrder() {
        when(jdbc.queryForObject(any(String.class), eq(Long.class), any(Object[].class)))
                .thenReturn(42L);
        List<SeckillOrderMessage> batch = Arrays.asList(
                new SeckillOrderMessage(7L, 101L, 9001L),
                new SeckillOrderMessage(7L, 102L, 9002L),
                new SeckillOrderMessage(7L, 103L, 9003L));

        notifier.notifySeckillBatch(batch);
        notifier.notifySeckillBatch(batch);

        verify(jdbc, atMostOnce()).queryForObject(any(String.class), eq(Long.class), any(Object[].class));
    }

    @Test
    void anEmptyBatchTouchesNothing() {
        notifier.notifySeckillBatch(List.of());

        verify(redis, times(0)).executePipelined(any(SessionCallback.class));
    }

    @Test
    void aFailedAnnouncementNeverPropagates() {
        when(jdbc.queryForObject(any(String.class), eq(Long.class), any(Object[].class)))
                .thenReturn(42L);
        when(redis.executePipelined(any(SessionCallback.class)))
                .thenThrow(new IllegalStateException("redis down"));

        // The durable status endpoint is authoritative; a pub/sub notification must never
        // cause an already-finalized DB/Redis transition to be redelivered.
        notifier.notifySeckillBatch(List.of(new SeckillOrderMessage(7L, 101L, 9001L)));

        assertThat(true).isTrue();
    }
}
