package com.localdeals.service;

import com.localdeals.config.BlogLikeProperties;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BlogLikeOutboxCleanupServiceTest {

    @Test
    void disabledWorkerDoesNotDeleteEvidence() {
        BlogLikeProperties properties = new BlogLikeProperties();
        properties.setWorkerEnabled(false);
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);

        new BlogLikeOutboxCleanupService(properties, jdbcTemplate).cleanup();

        verify(jdbcTemplate, never()).update(
                BlogLikeOutboxCleanupService.DELETE_PROCESSED_SQL,
                properties.getProcessedRetention().getSeconds(),
                properties.getCleanupBatchSize());
    }

    @Test
    void cleanupUsesBoundedConfiguredRetention() {
        BlogLikeProperties properties = new BlogLikeProperties();
        properties.setWorkerEnabled(true);
        properties.setProcessedRetention(Duration.ofDays(3));
        properties.setCleanupBatchSize(77);
        properties.setCleanupMaxBatches(3);
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        when(jdbcTemplate.update(
                BlogLikeOutboxCleanupService.DELETE_PROCESSED_SQL,
                Duration.ofDays(3).getSeconds(), 77)).thenReturn(77, 77, 3);

        new BlogLikeOutboxCleanupService(properties, jdbcTemplate).cleanup();

        verify(jdbcTemplate, org.mockito.Mockito.times(3)).update(
                BlogLikeOutboxCleanupService.DELETE_PROCESSED_SQL,
                Duration.ofDays(3).getSeconds(), 77);
    }
}
