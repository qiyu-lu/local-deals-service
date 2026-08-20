package com.localdeals.observability;

import com.localdeals.config.ObservabilityProperties;
import com.localdeals.service.BlogHotRankService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;

import static com.localdeals.utils.RedisConstants.SECKILL_PROCESSING_INDEX_KEY;
import static com.localdeals.utils.RedisConstants.SECKILL_PROCESSING_QUARANTINE_KEY;

/** Low-frequency, read-only sampling of reliability backlogs. */
@Component
@ConditionalOnProperty(
        prefix = "local-deals.observability",
        name = "sampling-enabled",
        havingValue = "true")
public class ReliabilityBacklogCollector {

    static final String OUTBOX_HEAD_SQL =
            "SELECT COUNT(*) AS pending_count, MIN(id) AS oldest_id " +
                    "FROM tb_blog_like_outbox FORCE INDEX (idx_blog_like_outbox_pending) " +
                    "WHERE processed_time IS NULL";
    static final String OUTBOX_CREATED_AT_SQL =
            "SELECT create_time FROM tb_blog_like_outbox WHERE id = ?";

    private static final DefaultRedisScript<String> SECKILL_BACKLOG_SCRIPT;

    static {
        SECKILL_BACKLOG_SCRIPT = new DefaultRedisScript<>();
        SECKILL_BACKLOG_SCRIPT.setLocation(
                new ClassPathResource("lua/seckill_processing_observability.lua"));
        SECKILL_BACKLOG_SCRIPT.setResultType(String.class);
    }

    private final ObservabilityProperties properties;
    private final JdbcTemplate jdbcTemplate;
    private final StringRedisTemplate redisTemplate;
    private final BlogHotRankService hotRankService;
    private final LocalDealsMetrics metrics;

    public ReliabilityBacklogCollector(ObservabilityProperties properties,
                                       JdbcTemplate jdbcTemplate,
                                       StringRedisTemplate redisTemplate,
                                       BlogHotRankService hotRankService,
                                       LocalDealsMetrics metrics) {
        this.properties = properties;
        this.jdbcTemplate = jdbcTemplate;
        this.redisTemplate = redisTemplate;
        this.hotRankService = hotRankService;
        this.metrics = metrics;
    }

    @Scheduled(
            initialDelayString = "#{@observabilityProperties.initialDelay.toMillis()}",
            fixedDelayString = "#{@observabilityProperties.samplingInterval.toMillis()}")
    public void collect() {
        if (!properties.isSamplingEnabled()) {
            return;
        }
        collectOutbox();
        collectSeckill();
        collectHotRank();
    }

    public void collectOutbox() {
        try {
            OutboxHead head = jdbcTemplate.queryForObject(OUTBOX_HEAD_SQL, (rs, rowNum) -> {
                Object oldestId = rs.getObject("oldest_id");
                if (oldestId != null && !(oldestId instanceof Number)) {
                    throw new IllegalStateException("Invalid oldest outbox id type");
                }
                return new OutboxHead(rs.getLong("pending_count"),
                        oldestId == null ? null : ((Number) oldestId).longValue());
            });
            if (head == null || head.pendingCount < 0L) {
                throw new IllegalStateException("Invalid outbox backlog sample");
            }
            if (head.pendingCount == 0L) {
                metrics.updateOutboxBacklog(0L, 0D);
                return;
            }
            if (head.oldestId == null || head.oldestId <= 0L) {
                throw new IllegalStateException("Pending outbox sample has no oldest id");
            }
            Timestamp createdAt = jdbcTemplate.queryForObject(
                    OUTBOX_CREATED_AT_SQL, Timestamp.class, head.oldestId);
            if (createdAt == null) {
                throw new IllegalStateException("Oldest pending outbox row disappeared");
            }
            double age = Math.max(0D,
                    Duration.between(createdAt.toInstant(), Instant.now()).toMillis() / 1000D);
            metrics.updateOutboxBacklog(head.pendingCount, age);
        } catch (RuntimeException failure) {
            metrics.failOutboxCollector();
        }
    }

    public void collectSeckill() {
        try {
            String sample = redisTemplate.execute(
                    SECKILL_BACKLOG_SCRIPT,
                    Arrays.asList(SECKILL_PROCESSING_INDEX_KEY,
                            SECKILL_PROCESSING_QUARANTINE_KEY));
            String[] fields = sample == null ? new String[0] : sample.split("\\|", -1);
            if (fields.length != 3) {
                throw new IllegalStateException("Malformed seckill backlog sample");
            }
            long due = nonNegativeLong(fields[0]);
            long oldest = nonNegativeLong(fields[1]);
            long quarantine = nonNegativeLong(fields[2]);
            metrics.updateSeckillBacklog(due, oldest, quarantine);
        } catch (RuntimeException failure) {
            metrics.failSeckillCollector();
        }
    }

    public void collectHotRank() {
        try {
            metrics.updateHotRankAge(hotRankService.publishedAgeSeconds());
        } catch (RuntimeException failure) {
            metrics.failHotRankCollector();
        }
    }

    private static long nonNegativeLong(String value) {
        if (value == null || !value.matches("0|[1-9][0-9]*")) {
            throw new IllegalStateException("Invalid non-negative sample value");
        }
        long parsed = Long.parseLong(value);
        if (parsed < 0L) {
            throw new IllegalStateException("Negative sample value");
        }
        return parsed;
    }

    private static final class OutboxHead {
        private final long pendingCount;
        private final Long oldestId;

        private OutboxHead(long pendingCount, Long oldestId) {
            this.pendingCount = pendingCount;
            this.oldestId = oldestId;
        }
    }
}
