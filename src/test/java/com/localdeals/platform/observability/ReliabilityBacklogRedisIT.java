package com.localdeals.platform.observability;

import com.localdeals.platform.config.ObservabilityProperties;
import com.localdeals.content.service.BlogHotRankService;
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

import com.localdeals.trade.service.SeckillBucketRouter;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

@SpringBootTest(classes = {RedisAutoConfiguration.class, ReliabilityBacklogRedisIT.Config.class})
@ActiveProfiles("test")
@org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(
        named = "M5A_ISOLATED", matches = "true")
class ReliabilityBacklogRedisIT {

    /** One bucket keeps the sample of this test a single index, as it was before M5. */
    private static final SeckillBucketRouter ROUTER = new SeckillBucketRouter(1);
    private static final String PROCESSING_KEY = ROUTER.processingKey(0);

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private ReliabilityBacklogCollector collector;

    @Autowired
    private MeterRegistry registry;

    @BeforeEach
    @AfterEach
    void cleanOwnedKeys() {
        redisTemplate.delete(PROCESSING_KEY);
    }

    @Test
    void readOnlyLuaReportsDueWithoutChangingRedisData() {
        long now = Instant.now().getEpochSecond();
        redisTemplate.opsForZSet().add(PROCESSING_KEY, "m5a-due", now - 7D);
        redisTemplate.opsForZSet().add(PROCESSING_KEY, "m5a-future", now + 600D);
        byte[] processingBefore = dump(PROCESSING_KEY);

        collector.collectSeckill();

        assertThat(registry.get("local_deals.seckill.processing.due").gauge().value())
                .isEqualTo(1D);
        assertThat(registry.get("local_deals.seckill.processing.oldest_overdue").gauge().value())
                .isBetween(7D, 10D);
        assertThat(dump(PROCESSING_KEY)).isEqualTo(processingBefore);
    }

    @Test
    void emptySetsAreRealZeroButWrongTypeIsUnavailable() {
        collector.collectSeckill();
        assertThat(registry.get("local_deals.seckill.processing.due").gauge().value()).isZero();

        redisTemplate.opsForValue().set(PROCESSING_KEY, "wrong-type");
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
                    properties, jdbcTemplate, redisTemplate, hotRankService, metrics, ROUTER);
        }
    }
}
