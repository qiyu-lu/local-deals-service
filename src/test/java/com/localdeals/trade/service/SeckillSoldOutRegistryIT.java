package com.localdeals.trade.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.test.context.ActiveProfiles;

import jakarta.annotation.Resource;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/** Two instances' registries over a real Redis: a flag set or cleared on one reaches the other. */
@SpringBootTest(classes = RedisAutoConfiguration.class)
@ActiveProfiles("test")
class SeckillSoldOutRegistryIT {

    private static final long VOUCHER = 77_401L;

    @Resource
    private StringRedisTemplate redis;

    private final List<RedisMessageListenerContainer> containers = new ArrayList<>();

    @AfterEach
    void stop() {
        containers.forEach(RedisMessageListenerContainer::stop);
    }

    private SeckillSoldOutRegistry instance() {
        SeckillSoldOutRegistry registry = new SeckillSoldOutRegistry(redis, Duration.ofMinutes(1),
                System::currentTimeMillis);
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(redis.getConnectionFactory());
        container.addMessageListener(registry, new ChannelTopic(SeckillSoldOutRegistry.CHANNEL));
        container.afterPropertiesSet();
        container.start();
        containers.add(container);
        return registry;
    }

    @Test
    void aFlagSetOnOneInstanceStopsTheOtherAndAReturnedUnitReopensBoth() {
        SeckillSoldOutRegistry first = instance();
        SeckillSoldOutRegistry second = instance();
        await().atMost(5, TimeUnit.SECONDS).until(() -> containers.stream().allMatch(c -> c.isRunning()));

        first.markSoldOut(VOUCHER);
        await().atMost(5, TimeUnit.SECONDS).until(() -> second.rejectLocally(VOUCHER));

        second.clear(VOUCHER);
        await().atMost(5, TimeUnit.SECONDS).until(() -> !first.rejectLocally(VOUCHER));
        assertThat(second.rejectLocally(VOUCHER)).isFalse();
    }
}
