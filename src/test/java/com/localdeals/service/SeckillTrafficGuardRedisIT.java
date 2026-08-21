package com.localdeals.service;

import com.localdeals.config.TrafficControlProperties;
import com.localdeals.exception.ApiStatusException;
import com.localdeals.observability.LocalDealsMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Duration;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(classes = {RedisAutoConfiguration.class, SeckillTrafficGuardRedisIT.Config.class})
@EnabledIfEnvironmentVariable(named = "M5C_ISOLATED", matches = "true")
class SeckillTrafficGuardRedisIT {
    private static final String RUN_ID = requiredEnv("M5C_RUN_ID");
    private static final long BASE_VOUCHER = Integer.toUnsignedLong(RUN_ID.hashCode()) + 1_000_000L;

    @Autowired
    private SeckillTrafficGuard guard;
    @Autowired
    private StringRedisTemplate redisTemplate;
    @Autowired
    private TrafficControlProperties properties;
    @Autowired
    private MeterRegistry meterRegistry;

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.redis.host", () -> requiredEnv("M5C_REDIS_HOST"));
        registry.add("spring.redis.port", () -> requiredEnv("M5C_REDIS_PORT"));
        registry.add("spring.redis.password", () -> requiredEnv("M5C_REDIS_PASSWORD"));
        registry.add("spring.redis.timeout", () -> "500ms");
        registry.add("spring.redis.lettuce.pool.max-wait", () -> "500ms");
    }

    @BeforeEach
    void verifyIsolationAndResetDefaults() {
        assertThat(redisTemplate.opsForValue().get("m5c:sentinel:" + RUN_ID))
                .isEqualTo(RUN_ID);
        properties.getSeckill().setEnabled(true);
        properties.getSeckill().setWindow(Duration.ofSeconds(10));
        properties.getSeckill().setActivityLimit(10);
        properties.getSeckill().setUserLimit(10);
        properties.getSeckill().setIpLimit(10);
        cleanup();
    }

    @AfterEach
    void cleanup() {
        for (long voucher = BASE_VOUCHER; voucher < BASE_VOUCHER + 10; voucher++) {
            Set<String> keys = redisTemplate.keys("traffic:seckill:{" + voucher + "}:*");
            if (keys != null && !keys.isEmpty()) {
                redisTemplate.delete(keys);
            }
        }
    }

    @Test
    void activityUserAndIpLimitsRejectWithoutConsumingOtherDimensions() {
        long activityVoucher = BASE_VOUCHER;
        properties.getSeckill().setActivityLimit(2);
        properties.getSeckill().setUserLimit(1);
        properties.getSeckill().setIpLimit(1);
        guard.check(activityVoucher, 1L, "203.0.113.1");
        guard.check(activityVoucher, 2L, "203.0.113.2");
        assertReason(() -> guard.check(activityVoucher, 3L, "203.0.113.3"), "activity");
        properties.getSeckill().setActivityLimit(10);
        guard.check(activityVoucher, 3L, "203.0.113.3");

        long userVoucher = BASE_VOUCHER + 1;
        properties.getSeckill().setUserLimit(1);
        properties.getSeckill().setIpLimit(10);
        guard.check(userVoucher, 11L, "203.0.113.11");
        assertReason(() -> guard.check(userVoucher, 11L, "203.0.113.12"), "user");
        guard.check(userVoucher, 12L, "203.0.113.12");

        long ipVoucher = BASE_VOUCHER + 2;
        properties.getSeckill().setUserLimit(10);
        properties.getSeckill().setIpLimit(1);
        guard.check(ipVoucher, 21L, "203.0.113.21");
        assertReason(() -> guard.check(ipVoucher, 22L, "203.0.113.21"), "ip");
        properties.getSeckill().setIpLimit(10);
        guard.check(ipVoucher, 22L, "203.0.113.21");
    }

    @Test
    void redisTimeWindowExpiresAndKeysHaveBoundedTtl() throws Exception {
        long voucher = BASE_VOUCHER + 3;
        properties.getSeckill().setWindow(Duration.ofSeconds(1));
        properties.getSeckill().setUserLimit(1);
        guard.check(voucher, 31L, "203.0.113.31");
        assertReason(() -> guard.check(voucher, 31L, "203.0.113.32"), "user");

        Set<String> keys = redisTemplate.keys("traffic:seckill:{" + voucher + "}:*");
        assertThat(keys).hasSize(3);
        for (String key : keys) {
            assertThat(redisTemplate.getExpire(key, TimeUnit.MILLISECONDS))
                    .isPositive().isLessThanOrEqualTo(3_000L);
        }

        Thread.sleep(1_100L);
        guard.check(voucher, 31L, "203.0.113.32");
    }

    private void assertReason(CheckedRunnable action, String reason) {
        double before = decisionCount(reason);
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(ApiStatusException.class, error ->
                        assertThat(error.getStatus()).isEqualTo(
                                org.springframework.http.HttpStatus.TOO_MANY_REQUESTS));
        assertThat(decisionCount(reason) - before).isEqualTo(1D);
    }

    private double decisionCount(String reason) {
        return meterRegistry.get("local_deals.traffic.decision")
                .tags("resource", "seckill", "result", "rejected", "reason", reason)
                .counter().count();
    }

    private static String requiredEnv(String name) {
        String value = System.getenv(name);
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalStateException(name + " must be set for isolated M5C IT");
        }
        return value;
    }

    @FunctionalInterface
    private interface CheckedRunnable {
        void run() throws Exception;
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(TrafficControlProperties.class)
    @Import(SeckillTrafficGuard.class)
    static class Config {
        @Bean
        MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }

        @Bean
        LocalDealsMetrics localDealsMetrics(MeterRegistry registry) {
            return new LocalDealsMetrics(registry);
        }
    }
}
