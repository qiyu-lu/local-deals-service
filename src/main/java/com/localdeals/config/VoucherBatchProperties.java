package com.localdeals.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.time.Duration;

/** Explicit gates and bounded batch settings for M6C workers. */
@Data
@Component
@ConfigurationProperties(prefix = "local-deals.voucher-batch")
public class VoucherBatchProperties {
    private static final int MAX_BATCH_SIZE = 500;
    private static final int MAX_ATTEMPTS = 100;

    private boolean jobWorkerEnabled = false;
    private boolean notificationWorkerEnabled = false;
    private Duration initialDelay = Duration.ofSeconds(5);
    private Duration fixedDelay = Duration.ofSeconds(1);
    private int batchSize = 50;
    private int notificationBatchSize = 50;
    private int notificationMaxAttempts = 10;
    private Duration notificationMaxBackoff = Duration.ofMinutes(5);

    @PostConstruct
    public void validate() {
        requirePositive(initialDelay, "initial-delay");
        requirePositive(fixedDelay, "fixed-delay");
        if (batchSize < 1 || batchSize > MAX_BATCH_SIZE) {
            throw new IllegalStateException("local-deals.voucher-batch.batch-size must be between 1 and " +
                    MAX_BATCH_SIZE);
        }
        if (notificationBatchSize < 1 || notificationBatchSize > MAX_BATCH_SIZE) {
            throw new IllegalStateException("local-deals.voucher-batch.notification-batch-size must be between 1 and " +
                    MAX_BATCH_SIZE);
        }
        if (notificationMaxAttempts < 1 || notificationMaxAttempts > MAX_ATTEMPTS) {
            throw new IllegalStateException("local-deals.voucher-batch.notification-max-attempts must be between 1 and " +
                    MAX_ATTEMPTS);
        }
        requirePositive(notificationMaxBackoff, "notification-max-backoff");
    }

    private static void requirePositive(Duration value, String name) {
        if (value == null || value.isZero() || value.isNegative() || value.toMillis() <= 0L) {
            throw new IllegalStateException("local-deals.voucher-batch." + name + " must be positive");
        }
    }
}
