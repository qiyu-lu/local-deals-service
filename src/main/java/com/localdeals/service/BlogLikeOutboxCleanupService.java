package com.localdeals.service;

import com.localdeals.config.BlogLikeProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/** Bounded retention cleanup for already committed outbox evidence. */
@Slf4j
@Service
public class BlogLikeOutboxCleanupService {

    static final String DELETE_PROCESSED_SQL =
            "DELETE FROM tb_blog_like_outbox " +
                    "WHERE processed_time IS NOT NULL " +
                    "AND processed_time < TIMESTAMPADD(SECOND, -?, CURRENT_TIMESTAMP(3)) " +
                    "ORDER BY processed_time, id LIMIT ?";

    private final BlogLikeProperties properties;
    private final JdbcTemplate jdbcTemplate;

    public BlogLikeOutboxCleanupService(BlogLikeProperties properties, JdbcTemplate jdbcTemplate) {
        this.properties = properties;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Scheduled(
            initialDelayString = "#{@blogLikeProperties.initialDelay.toMillis()}",
            fixedDelayString = "#{@blogLikeProperties.cleanupFixedDelay.toMillis()}")
    public void cleanup() {
        if (!properties.isWorkerEnabled()) {
            return;
        }
        try {
            int totalDeleted = 0;
            for (int batch = 0; batch < properties.getCleanupMaxBatches(); batch++) {
                int deleted = jdbcTemplate.update(
                        DELETE_PROCESSED_SQL,
                        properties.getProcessedRetention().getSeconds(),
                        properties.getCleanupBatchSize());
                totalDeleted += deleted;
                if (deleted < properties.getCleanupBatchSize()) {
                    break;
                }
            }
            if (totalDeleted > 0) {
                log.info("Deleted expired processed blog-like outbox rows. count={}", totalDeleted);
            }
        } catch (RuntimeException e) {
            // Cleanup never changes pending events and must not affect the aggregate worker.
            log.warn("Unable to clean processed blog-like outbox rows", e);
        }
    }
}
