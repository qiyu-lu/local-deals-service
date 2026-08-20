package com.localdeals.config;

import org.apache.commons.pool2.impl.GenericObjectPoolConfig;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettucePoolingClientConfiguration;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class RedisTimeoutConfigurationTest {

    @Test
    void bootContextAppliesCommandTimeoutAndPoolMaxWait() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(RedisAutoConfiguration.class))
                .withPropertyValues(
                        "spring.redis.host=127.0.0.1",
                        "spring.redis.port=6399",
                        "spring.redis.timeout=500ms",
                        "spring.redis.lettuce.pool.max-wait=500ms")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    LettuceConnectionFactory factory = context.getBean(LettuceConnectionFactory.class);
                    LettuceClientConfiguration clientConfiguration = factory.getClientConfiguration();
                    assertThat(clientConfiguration.getCommandTimeout())
                            .isEqualTo(Duration.ofMillis(500));
                    assertThat(clientConfiguration)
                            .isInstanceOf(LettucePoolingClientConfiguration.class);
                    GenericObjectPoolConfig<?> poolConfig =
                            ((LettucePoolingClientConfiguration) clientConfiguration).getPoolConfig();
                    assertThat(poolConfig.getMaxWaitMillis()).isEqualTo(500L);
                });
    }
}
