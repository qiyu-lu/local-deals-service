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
    public static class Reconciliation {
        private static final int MAX_BATCH_SIZE = 1_000;

        /** Master switch for the scheduled reconciliation worker. */
        private boolean enabled = false;
        /** Destructive timeout compensation requires an additional explicit switch. */
        private boolean compensationEnabled = false;
        private Duration initialDelay = Duration.ofSeconds(30);
        private Duration fixedDelay = Duration.ofSeconds(10);
        private Duration staleAfter = Duration.ofMinutes(2);
        private Duration retryDelay = Duration.ofMinutes(1);
        private Duration finalTimeout = Duration.ofMinutes(15);
        private int batchSize = 100;

        public void validate() {
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
