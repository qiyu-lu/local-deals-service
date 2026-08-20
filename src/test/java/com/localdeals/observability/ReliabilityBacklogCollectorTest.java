package com.localdeals.observability;

import com.localdeals.config.ObservabilityProperties;
import com.localdeals.service.BlogHotRankService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ReliabilityBacklogCollectorTest {

    private JdbcTemplate jdbcTemplate;
    private StringRedisTemplate redisTemplate;
    private BlogHotRankService hotRankService;
    private SimpleMeterRegistry registry;
    private ReliabilityBacklogCollector collector;

    @BeforeEach
    void setUp() {
        ObservabilityProperties properties = new ObservabilityProperties();
        properties.setSamplingEnabled(true);
        jdbcTemplate = mock(JdbcTemplate.class);
        redisTemplate = mock(StringRedisTemplate.class);
        hotRankService = mock(BlogHotRankService.class);
        registry = new SimpleMeterRegistry();
        collector = new ReliabilityBacklogCollector(properties, jdbcTemplate, redisTemplate,
                hotRankService, new LocalDealsMetrics(registry));
    }

    @Test
    @SuppressWarnings("unchecked")
    void emptyOutboxIsARealZeroAndDatabaseFailureBecomesUnavailableThenRecovers() throws Exception {
        ResultSet resultSet = mock(ResultSet.class);
        when(resultSet.getLong("pending_count")).thenReturn(0L);
        when(resultSet.getObject("oldest_id")).thenReturn(null);
        when(jdbcTemplate.queryForObject(eq(ReliabilityBacklogCollector.OUTBOX_HEAD_SQL),
                any(RowMapper.class))).thenAnswer(invocation ->
                ((RowMapper<?>) invocation.getArgument(1)).mapRow(resultSet, 0));

        collector.collectOutbox();
        assertThat(registry.get("local_deals.blog.like.outbox.pending").gauge().value()).isZero();

        when(jdbcTemplate.queryForObject(eq(ReliabilityBacklogCollector.OUTBOX_HEAD_SQL),
                any(RowMapper.class))).thenThrow(new IllegalStateException("db unavailable"));
        collector.collectOutbox();
        assertThat(registry.get("local_deals.blog.like.outbox.pending").gauge().value()).isNaN();

        when(jdbcTemplate.queryForObject(eq(ReliabilityBacklogCollector.OUTBOX_HEAD_SQL),
                any(RowMapper.class))).thenAnswer(invocation ->
                ((RowMapper<?>) invocation.getArgument(1)).mapRow(resultSet, 0));
        collector.collectOutbox();
        assertThat(registry.get("local_deals.blog.like.outbox.pending").gauge().value()).isZero();
        assertThat(registry.get("local_deals.blog.like.outbox.collector")
                .tag("result", "failure").counter().count()).isEqualTo(1D);
    }

    @Test
    @SuppressWarnings("unchecked")
    void pendingOutboxUsesTheIndexedHeadThenPrimaryKeyTimestamp() throws Exception {
        ResultSet resultSet = mock(ResultSet.class);
        when(resultSet.getLong("pending_count")).thenReturn(3L);
        when(resultSet.getObject("oldest_id")).thenReturn(17L);
        when(jdbcTemplate.queryForObject(eq(ReliabilityBacklogCollector.OUTBOX_HEAD_SQL),
                any(RowMapper.class))).thenAnswer(invocation ->
                ((RowMapper<?>) invocation.getArgument(1)).mapRow(resultSet, 0));
        when(jdbcTemplate.queryForObject(
                eq(ReliabilityBacklogCollector.OUTBOX_CREATED_AT_SQL),
                eq(Timestamp.class), eq(17L)))
                .thenReturn(Timestamp.from(Instant.now().minusSeconds(5)));

        collector.collectOutbox();

        assertThat(registry.get("local_deals.blog.like.outbox.pending").gauge().value())
                .isEqualTo(3D);
        assertThat(registry.get("local_deals.blog.like.outbox.oldest_age").gauge().value())
                .isBetween(4D, 7D);
    }

    @Test
    @SuppressWarnings("unchecked")
    void malformedRedisSampleIsUnavailableAndRecoveryRestoresValues() {
        when(redisTemplate.execute(any(RedisScript.class), anyList())).thenReturn("broken");
        collector.collectSeckill();
        assertThat(registry.get("local_deals.seckill.processing.due").gauge().value()).isNaN();

        when(redisTemplate.execute(any(RedisScript.class), anyList())).thenReturn("4|11|2");
        collector.collectSeckill();
        assertThat(registry.get("local_deals.seckill.processing.due").gauge().value())
                .isEqualTo(4D);
        assertThat(registry.get("local_deals.seckill.processing.oldest_overdue").gauge().value())
                .isEqualTo(11D);
        assertThat(registry.get("local_deals.seckill.processing.quarantine").gauge().value())
                .isEqualTo(2D);
    }

    @Test
    void missingHotRankMetadataIsUnavailableAndRecoveryUsesAge() {
        when(hotRankService.publishedAgeSeconds())
                .thenThrow(new IllegalStateException("missing"))
                .thenReturn(8.25D);

        collector.collectHotRank();
        assertThat(registry.get("local_deals.blog.hot_rank.age").gauge().value()).isNaN();
        collector.collectHotRank();
        assertThat(registry.get("local_deals.blog.hot_rank.age").gauge().value())
                .isEqualTo(8.25D);
    }
}
