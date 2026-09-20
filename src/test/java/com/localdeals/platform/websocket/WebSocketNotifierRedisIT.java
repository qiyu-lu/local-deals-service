package com.localdeals.platform.websocket;

import com.localdeals.trade.mq.SeckillOrderMessage;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.mock;

/**
 * The batch announcement publishes through one pipelined session. Pipelining behaves differently
 * on a Cluster than on a single node, and a failure here is swallowed by design (the durable
 * status endpoint is authoritative), so nothing else would notice that the notifications stopped
 * arriving. This test runs against whichever Redis the stack is started with.
 */
@SpringBootTest(classes = RedisAutoConfiguration.class)
@ActiveProfiles("test")
class WebSocketNotifierRedisIT {

    private static final long USER_A = 88_100_001L;
    private static final long USER_B = 88_100_002L;

    @Resource
    private StringRedisTemplate redis;

    private RedisMessageListenerContainer container;

    @AfterEach
    void stopContainer() throws Exception {
        if (container != null) {
            container.stop();
            container.destroy();
            container = null;
        }
    }

    @Test
    void aBatchAnnouncementReachesEveryBuyersChannel() {
        List<String> received = new CopyOnWriteArrayList<>();
        container = new RedisMessageListenerContainer();
        container.setConnectionFactory(redis.getConnectionFactory());
        container.addMessageListener(
                (message, pattern) -> received.add(new String(message.getBody(), StandardCharsets.UTF_8)),
                List.of(new ChannelTopic(WebSocketNotifier.CHANNEL_PREFIX + USER_A),
                        new ChannelTopic(WebSocketNotifier.CHANNEL_PREFIX + USER_B)));
        container.afterPropertiesSet();
        container.start();
        await().atMost(5, TimeUnit.SECONDS).until(container::isRunning);

        WebSocketNotifier notifier = new WebSocketNotifier();
        ReflectionTestUtils.setField(notifier, "stringRedisTemplate", redis);
        // The merchant lookup is not what this test is about; a null merchant only drops the
        // merchant channel, the buyer and platform ones are unaffected.
        ReflectionTestUtils.setField(notifier, "jdbcTemplate", mock(JdbcTemplate.class));

        notifier.notifySeckillBatch(List.of(
                new SeckillOrderMessage(7L, USER_A, 900_001L),
                new SeckillOrderMessage(7L, USER_B, 900_002L)));

        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> assertThat(received).hasSize(2));
        assertThat(received).anySatisfy(json -> assertThat(json).contains("900001"))
                .anySatisfy(json -> assertThat(json).contains("900002"));
    }
}
