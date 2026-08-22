package com.localdeals.marketing;

import com.localdeals.entity.VoucherGrantNotificationOutbox;
import com.localdeals.service.VoucherGrantNotificationOutboxService;
import com.localdeals.websocket.WebSocketNotifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;

/** M6C MySQL plus the run-owned Redis Pub/Sub producer, without MQ/ES/WebSocket listeners. */
@Configuration(proxyBeanMethods = false)
@Import({M6cPersistenceTestConfiguration.class, VoucherGrantNotificationOutboxService.class})
public class M6cOutboxPersistenceTestConfiguration {

    @Bean(destroyMethod = "destroy")
    public LettuceConnectionFactory m6cRedisConnectionFactory() {
        LettuceClientConfiguration client = LettuceClientConfiguration.builder()
                .commandTimeout(Duration.ofSeconds(2)).build();
        LettuceConnectionFactory factory = new LettuceConnectionFactory(
                new RedisStandaloneConfiguration(M6cRedisGuard.host(), M6cRedisGuard.port()), client);
        factory.afterPropertiesSet();
        return factory;
    }

    @Bean
    public StringRedisTemplate m6cStringRedisTemplate(LettuceConnectionFactory factory) {
        StringRedisTemplate template = new StringRedisTemplate(factory);
        template.afterPropertiesSet();
        return template;
    }

    @Bean
    public WebSocketNotifier m6cWebSocketNotifier(StringRedisTemplate redisTemplate,
            JdbcTemplate jdbcTemplate) {
        WebSocketNotifier notifier = new WebSocketNotifier();
        ReflectionTestUtils.setField(notifier, "stringRedisTemplate", redisTemplate);
        ReflectionTestUtils.setField(notifier, "jdbcTemplate", jdbcTemplate);
        return notifier;
    }
}
