package com.localdeals.service;

import com.localdeals.config.TrafficControlProperties;
import com.localdeals.exception.ApiErrorCodes;
import com.localdeals.exception.ApiStatusException;
import com.localdeals.observability.LocalDealsMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SeckillTrafficGuardTest {
    private StringRedisTemplate redisTemplate;
    private TrafficControlProperties properties;
    private SimpleMeterRegistry registry;
    private SeckillTrafficGuard guard;

    @BeforeEach
    void setUp() {
        redisTemplate = mock(StringRedisTemplate.class);
        properties = new TrafficControlProperties();
        registry = new SimpleMeterRegistry();
        guard = new SeckillTrafficGuard(redisTemplate, properties, new LocalDealsMetrics(registry));
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void passesHashedKeysAndFixedLimitsToServerTimeLua() {
        when(redisTemplate.execute(any(RedisScript.class), anyList(),
                any(), any(), any(), any())).thenReturn(0L);

        guard.check(17L, 23L, "203.0.113.9");

        org.mockito.ArgumentCaptor<List> keys = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(redisTemplate).execute(any(RedisScript.class), keys.capture(),
                eq("1000"), eq("300"), eq("2"), eq("100"));
        assertThat(keys.getValue()).containsExactly(
                "traffic:seckill:{17}:activity:",
                "traffic:seckill:{17}:user:23:",
                "traffic:seckill:{17}:ip:d861b7e91033ebc1c1e8e7af3929010158b3241b54ca87ef73e79c32f26400ec:");
        assertThat(counter("allowed", "none")).isEqualTo(1D);
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void mapsAllFixedRejectionsWithoutDynamicMetricTags() {
        for (long result = 1; result <= 3; result++) {
            when(redisTemplate.execute(any(RedisScript.class), anyList(),
                    any(), any(), any(), any())).thenReturn(result);

            assertThatThrownBy(() -> guard.check(17L, 23L, "203.0.113.9"))
                    .isInstanceOfSatisfying(ApiStatusException.class, error -> {
                        assertThat(error.getStatus().value()).isEqualTo(429);
                        assertThat(error.getCode()).isEqualTo(ApiErrorCodes.SECKILL_RATE_LIMITED);
                    });
        }
        assertThat(counter("rejected", "activity")).isEqualTo(1D);
        assertThat(counter("rejected", "user")).isEqualTo(1D);
        assertThat(counter("rejected", "ip")).isEqualTo(1D);
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void nullUnknownAndRedisFailureFailClosed() {
        when(redisTemplate.execute(any(RedisScript.class), anyList(),
                any(), any(), any(), any())).thenReturn(null, 9L)
                .thenThrow(new RuntimeException("redis unavailable"));

        for (int attempt = 0; attempt < 3; attempt++) {
            assertThatThrownBy(() -> guard.check(17L, 23L, "203.0.113.9"))
                    .isInstanceOfSatisfying(ApiStatusException.class, error -> {
                        assertThat(error.getStatus().value()).isEqualTo(503);
                        assertThat(error.getCode()).isEqualTo(ApiErrorCodes.SECKILL_SUBMIT_UNAVAILABLE);
                    });
        }
        assertThat(counter("unavailable", "redis")).isEqualTo(3D);
    }

    @Test
    void disabledSwitchBypassesRedisButStillRecordsAllowedDecision() {
        properties.getSeckill().setEnabled(false);

        guard.check(17L, 23L, "203.0.113.9");

        verify(redisTemplate, never()).execute(any(RedisScript.class), anyList(),
                any(), any(), any(), any());
        assertThat(counter("allowed", "none")).isEqualTo(1D);
    }

    @Test
    void luaContractUsesRedisTimeChecksBeforeWritesAndBoundsTtl() throws Exception {
        String script = new String(org.springframework.util.StreamUtils.copyToByteArray(
                new org.springframework.core.io.ClassPathResource(
                        "lua/seckill_traffic_guard.lua").getInputStream()), java.nio.charset.StandardCharsets.UTF_8);

        assertThat(script).contains("redis.call('TIME')")
                .contains("return 1", "return 2", "return 3")
                .contains("redis.call('INCR', activityKey)")
                .contains("redis.call('PEXPIRE', activityKey, ttlMillis)");
        assertThat(script.indexOf("if activityCount >= activityLimit"))
                .isLessThan(script.indexOf("redis.call('INCR', activityKey)"));
        assertThat(script.indexOf("if userCount >= userLimit"))
                .isLessThan(script.indexOf("redis.call('INCR', activityKey)"));
        assertThat(script.indexOf("if ipCount >= ipLimit"))
                .isLessThan(script.indexOf("redis.call('INCR', activityKey)"));
        properties.getSeckill().setWindow(Duration.ofSeconds(60));
        properties.validate();
    }

    private double counter(String result, String reason) {
        return registry.get("local_deals.traffic.decision")
                .tags("resource", "seckill", "result", result, "reason", reason)
                .counter().count();
    }
}
