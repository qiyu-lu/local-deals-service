package com.localdeals.content.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.time.Duration;

/** Configuration for the durable blog-like outbox projection. */
@Data
@Component
@ConfigurationProperties(prefix = "local-deals.blog-like")
public class BlogLikeProperties {

    private static final int MAX_BATCH_SIZE = 5_000;

    /** Allows the scheduled aggregate worker to consume committed outbox rows. */
    private boolean workerEnabled = true;
    private Duration initialDelay = Duration.ofSeconds(5);
    private Duration fixedDelay = Duration.ofMillis(200);
    private int batchSize = 500;
    private Duration processedRetention = Duration.ofDays(1);
    private Duration cleanupFixedDelay = Duration.ofSeconds(1);
    private int cleanupBatchSize = 2_000;
    /** Maximum bounded delete batches per cleanup tick. */
    private int cleanupMaxBatches = 5;

    @PostConstruct
    public void validate() {
        requirePositive(initialDelay, "initial-delay");
        requirePositive(fixedDelay, "fixed-delay");
        requirePositive(processedRetention, "processed-retention");
        requirePositive(cleanupFixedDelay, "cleanup-fixed-delay");
        if (batchSize < 1 || batchSize > MAX_BATCH_SIZE) {
            throw new IllegalStateException(
                    "local-deals.blog-like.batch-size must be between 1 and " + MAX_BATCH_SIZE);
        }
        if (cleanupBatchSize < 1 || cleanupBatchSize > MAX_BATCH_SIZE) {
            throw new IllegalStateException(
                    "local-deals.blog-like.cleanup-batch-size must be between 1 and " + MAX_BATCH_SIZE);
        }
        if (cleanupMaxBatches < 1 || cleanupMaxBatches > 100) {
            throw new IllegalStateException(
                    "local-deals.blog-like.cleanup-max-batches must be between 1 and 100");
        }
    }

    private static void requirePositive(Duration value, String name) {
        if (value == null || value.isZero() || value.isNegative() || value.toMillis() <= 0L) {
            throw new IllegalStateException("local-deals.blog-like." + name + " must be positive");
        }
    }
}
