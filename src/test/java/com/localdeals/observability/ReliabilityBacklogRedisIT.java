package com.localdeals.observability;

import com.localdeals.config.ObservabilityProperties;
import com.localdeals.service.BlogHotRankService;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

import static com.localdeals.utils.RedisConstants.SECKILL_PROCESSING_INDEX_KEY;
import static com.localdeals.utils.RedisConstants.SECKILL_PROCESSING_QUARANTINE_KEY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

@SpringBootTest(classes = {RedisAutoConfiguration.class, ReliabilityBacklogRedisIT.Config.class})
@ActiveProfiles("test")
@org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(
        named = "M5A_ISOLATED", matches = "true")
class ReliabilityBacklogRedisIT {

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private ReliabilityBacklogCollector collector;

    @Autowired
    private MeterRegistry registry;

    @BeforeEach
    @AfterEach
    void cleanOwnedKeys() {
        redisTemplate.delete(SECKILL_PROCESSING_INDEX_KEY);
        redisTemplate.delete(SECKILL_PROCESSING_QUARANTINE_KEY);
    }

    @Test
    void readOnlyLuaReportsDueAndQuarantineWithoutChangingRedisData() {
        long now = Instant.now().getEpochSecond();
        redisTemplate.opsForZSet().add(SECKILL_PROCESSING_INDEX_KEY, "m5a-due", now - 7D);
        redisTemplate.opsForZSet().add(SECKILL_PROCESSING_INDEX_KEY, "m5a-future", now + 600D);
        redisTemplate.opsForZSet().add(SECKILL_PROCESSING_QUARANTINE_KEY, "m5a-quarantine", now);
        byte[] processingBefore = dump(SECKILL_PROCESSING_INDEX_KEY);
        byte[] quarantineBefore = dump(SECKILL_PROCESSING_QUARANTINE_KEY);

        collector.collectSeckill();

        assertThat(registry.get("local_deals.seckill.processing.due").gauge().value())
                .isEqualTo(1D);
        assertThat(registry.get("local_deals.seckill.processing.oldest_overdue").gauge().value())
                .isBetween(7D, 10D);
        assertThat(registry.get("local_deals.seckill.processing.quarantine").gauge().value())
                .isEqualTo(1D);
        assertThat(dump(SECKILL_PROCESSING_INDEX_KEY)).isEqualTo(processingBefore);
        assertThat(dump(SECKILL_PROCESSING_QUARANTINE_KEY)).isEqualTo(quarantineBefore);
    }

    @Test
    void emptySetsAreRealZeroButWrongTypeIsUnavailable() {
        collector.collectSeckill();
        assertThat(registry.get("local_deals.seckill.processing.due").gauge().value()).isZero();

        redisTemplate.opsForValue().set(SECKILL_PROCESSING_INDEX_KEY, "wrong-type");
        collector.collectSeckill();

        assertThat(registry.get("local_deals.seckill.processing.due").gauge().value()).isNaN();
        assertThat(registry.get("local_deals.seckill.processing.oldest_overdue").gauge().value())
                .isNaN();
        assertThat(registry.get("local_deals.seckill.processing.collector")
                .tag("result", "failure").counter().count()).isEqualTo(1D);
    }

    private byte[] dump(String key) {
        return redisTemplate.execute((RedisCallback<byte[]>) connection ->
                connection.dump(key.getBytes(StandardCharsets.UTF_8)));
    }

    @TestConfiguration
    static class Config {
        @Bean
        ObservabilityProperties observabilityProperties() {
            ObservabilityProperties properties = new ObservabilityProperties();
            properties.setSamplingEnabled(true);
            return properties;
        }

        @Bean
        MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }

        @Bean
        LocalDealsMetrics localDealsMetrics(MeterRegistry registry) {
            return new LocalDealsMetrics(registry);
        }

        @Bean
        JdbcTemplate jdbcTemplate() {
            return mock(JdbcTemplate.class);
        }

        @Bean
        BlogHotRankService blogHotRankService() {
            return mock(BlogHotRankService.class);
        }

        @Bean
        ReliabilityBacklogCollector reliabilityBacklogCollector(
                ObservabilityProperties properties,
                JdbcTemplate jdbcTemplate,
                StringRedisTemplate redisTemplate,
                BlogHotRankService hotRankService,
                LocalDealsMetrics metrics) {
            return new ReliabilityBacklogCollector(
                    properties, jdbcTemplate, redisTemplate, hotRankService, metrics);
        }
    }
}
