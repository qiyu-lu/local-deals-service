package com.localdeals.trade.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.time.Duration;

@Data
@Component
@ConfigurationProperties(prefix = "local-deals.order")
public class OrderProperties {

    /** How long a PENDING_PAY order waits for payment before it is closed and its stock returns. */
    private Duration payTimeout = Duration.ofMinutes(15);
    /** Timer-message topic that closes an order at its deadline. */
    private String closeTopic = "order-close-topic";
    private String closeConsumerGroup = "order-close-consumer-group";
    /** Fallback scan for lost timer messages and unfinished Redis releases. */
    private Scan scan = new Scan();
    /** Redis lease on this instance's Snowflake worker id; renewed every third of it. */
    private Duration workerLeaseTtl = Duration.ofSeconds(30);

    @PostConstruct
    public void validate() {
        if (payTimeout == null || payTimeout.getSeconds() < 1) {
            throw new IllegalStateException("local-deals.order.pay-timeout must be at least one second");
        }
        if (closeTopic == null || closeTopic.isBlank() || closeConsumerGroup == null || closeConsumerGroup.isBlank()) {
            throw new IllegalStateException("local-deals.order close-topic and close-consumer-group must not be blank");
        }
        if (workerLeaseTtl == null || workerLeaseTtl.getSeconds() < 3) {
            throw new IllegalStateException("local-deals.order.worker-lease-ttl must be at least three seconds");
        }
        scan.validate();
    }

    @Data
    public static class Scan {
        private boolean enabled = true;
        private Duration initialDelay = Duration.ofSeconds(20);
        private Duration fixedDelay = Duration.ofSeconds(10);
        /**
         * The timer message is the primary close path; the scan only takes orders this long past
         * their deadline, so the two paths rarely race (racing is still safe: CAS).
         */
        private Duration grace = Duration.ofSeconds(30);
        /** A release left pending this long after the close/refund commit is retried by the scan. */
        private Duration releaseRetryAfter = Duration.ofSeconds(10);
        private int batchSize = 200;

        void validate() {
            if (fixedDelay == null || fixedDelay.isZero() || fixedDelay.isNegative() ||
                    initialDelay == null || initialDelay.isNegative() ||
                    grace == null || grace.isNegative() ||
                    releaseRetryAfter == null || releaseRetryAfter.isNegative() ||
                    batchSize < 1 || batchSize > 1_000) {
                throw new IllegalStateException("local-deals.order.scan settings are out of range");
            }
        }
    }
}
