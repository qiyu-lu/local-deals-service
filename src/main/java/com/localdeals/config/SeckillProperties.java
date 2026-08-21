package com.localdeals.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.time.Duration;
import java.util.Locale;

@Data
@Component
@ConfigurationProperties(prefix = "local-deals.seckill")
public class SeckillProperties {

    private String topic = "seckill-order-topic";
    private String consumerGroup = "seckill-consumer-group";
    private Stream stream = new Stream();
    private Reconciliation reconciliation = new Reconciliation();

    @PostConstruct
    public void validate() {
        if (topic == null || topic.trim().isEmpty() ||
                consumerGroup == null || consumerGroup.trim().isEmpty()) {
            throw new IllegalStateException("local-deals.seckill topic and consumer-group must not be blank");
        }
        if (reconciliation == null) {
            throw new IllegalStateException("local-deals.seckill.reconciliation must not be null");
        }
        reconciliation.validate();
    }

    @Data
    public static class Stream {
        private String key = "stream.orders";
        private String group = "g1";
        private String consumer = "c1";
        private String deadLetterKey = "stream.orders.dlq";
        private String retryKeyPrefix = "seckill:stream:retry:";
        private int readCount = 10;
        private int workerCount = 1;
        private int maxRetry = 3;
        private Duration block = Duration.ofSeconds(2);
    }

    @Data
    public static class Reconciliation {
        private static final int MAX_BATCH_SIZE = 1_000;
        private static final int MAX_SCAN_COUNT = 10_000;

        /** Master switch for the scheduled reconciliation worker. */
        private boolean enabled = false;
        /** Destructive timeout compensation requires an additional explicit switch. */
        private boolean compensationEnabled = false;
        /** One-time exact PROCESSING index backfill is opt-in. */
        private boolean backfillOnStartup = false;
        private Duration initialDelay = Duration.ofSeconds(30);
        private Duration fixedDelay = Duration.ofSeconds(10);
        private Duration staleAfter = Duration.ofMinutes(2);
        private Duration retryDelay = Duration.ofMinutes(1);
        private Duration finalTimeout = Duration.ofMinutes(15);
        private int batchSize = 100;
        private int scanCount = 500;

        public void validate() {
            if (backfillOnStartup && enabled) {
                throw new IllegalStateException(
                        "local-deals.seckill.reconciliation.backfill-on-startup and enabled " +
                                "must not be true in the same startup");
            }
            requirePositiveSeconds(initialDelay, "initialDelay");
            requirePositiveSeconds(fixedDelay, "fixedDelay");
            requirePositiveSeconds(staleAfter, "staleAfter");
            requirePositiveSeconds(retryDelay, "retryDelay");
            requirePositiveSeconds(finalTimeout, "finalTimeout");
            if (finalTimeout.compareTo(staleAfter) <= 0) {
                throw new IllegalStateException(
                        "local-deals.seckill.reconciliation.final-timeout must be greater than stale-after");
            }
            if (batchSize < 1 || batchSize > MAX_BATCH_SIZE) {
                throw new IllegalStateException(
                        "local-deals.seckill.reconciliation.batch-size must be between 1 and " + MAX_BATCH_SIZE);
            }
            if (scanCount < 1 || scanCount > MAX_SCAN_COUNT) {
                throw new IllegalStateException(
                        "local-deals.seckill.reconciliation.scan-count must be between 1 and " + MAX_SCAN_COUNT);
            }
        }

        private static void requirePositiveSeconds(Duration value, String name) {
            if (value == null || value.getSeconds() <= 0) {
                throw new IllegalStateException(
                        "local-deals.seckill.reconciliation." + toKebabCase(name) +
                                " must be at least one second");
            }
        }

        private static String toKebabCase(String value) {
            return value.replaceAll("([a-z])([A-Z])", "$1-$2").toLowerCase(Locale.ROOT);
        }
    }
}
