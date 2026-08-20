package com.localdeals.observability;

import com.localdeals.config.ObservabilityProperties;
import com.localdeals.service.BlogHotRankService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

@org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(
        named = "M5A_ISOLATED", matches = "true")
class ReliabilityBacklogMySqlIT {

    private static final long FIRST_ID = 8_805_000_001L;
    private static final long SECOND_ID = 8_805_000_002L;

    private JdbcTemplate jdbcTemplate;
    private SimpleMeterRegistry registry;
    private ReliabilityBacklogCollector collector;

    @BeforeEach
    void setUp() {
        String url = required("M5A_MYSQL_URL");
        assertThat(url).contains("m5a_").doesNotContain("/local_deals", "/hmdp");
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                url, required("M5A_MYSQL_USER"), required("M5A_MYSQL_PASSWORD"));
        dataSource.setDriverClassName("com.mysql.jdbc.Driver");
        jdbcTemplate = new JdbcTemplate(dataSource);
        jdbcTemplate.queryForObject("SELECT COUNT(*) FROM tb_blog_like_outbox", Long.class);
        cleanupOwnedRows();
        registry = new SimpleMeterRegistry();
        ObservabilityProperties properties = new ObservabilityProperties();
        properties.setSamplingEnabled(true);
        collector = new ReliabilityBacklogCollector(properties, jdbcTemplate,
                mock(StringRedisTemplate.class), mock(BlogHotRankService.class),
                new LocalDealsMetrics(registry));
    }

    @AfterEach
    void cleanupOwnedRows() {
        if (jdbcTemplate != null) {
            jdbcTemplate.update("DELETE FROM tb_blog_like_outbox WHERE id IN (?, ?)",
                    FIRST_ID, SECOND_ID);
        }
    }

    @Test
    void indexedBacklogSampleDistinguishesEmptyPendingAndRecovery() {
        collector.collectOutbox();
        assertThat(registry.get("local_deals.blog.like.outbox.pending").gauge().value()).isZero();

        jdbcTemplate.update("INSERT INTO tb_blog_like_outbox " +
                        "(id, blog_id, delta, create_time, processed_time) VALUES (?, 4, 1, ?, NULL)",
                FIRST_ID, Timestamp.from(Instant.now().minusSeconds(8)));
        jdbcTemplate.update("INSERT INTO tb_blog_like_outbox " +
                        "(id, blog_id, delta, create_time, processed_time) VALUES (?, 4, -1, ?, NULL)",
                SECOND_ID, Timestamp.from(Instant.now().minusSeconds(3)));

        Map<String, Object> plan = jdbcTemplate.queryForMap(
                "EXPLAIN " + ReliabilityBacklogCollector.OUTBOX_HEAD_SQL);
        assertThat(String.valueOf(plan.get("key")))
                .isEqualTo("idx_blog_like_outbox_pending");

        collector.collectOutbox();
        assertThat(registry.get("local_deals.blog.like.outbox.pending").gauge().value())
                .isEqualTo(2D);
        assertThat(registry.get("local_deals.blog.like.outbox.oldest_age").gauge().value())
                .isBetween(8D, 11D);

        cleanupOwnedRows();
        collector.collectOutbox();
        assertThat(registry.get("local_deals.blog.like.outbox.pending").gauge().value()).isZero();
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalStateException(name + " is required for isolated M5A tests");
        }
        return value;
    }
}
