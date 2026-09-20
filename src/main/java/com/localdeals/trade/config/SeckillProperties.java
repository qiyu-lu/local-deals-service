package com.localdeals.trade.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.time.Duration;
import java.util.Locale;

@Data
@Component
@ConfigurationProperties(prefix = "local-deals.seckill")
public class SeckillProperties {

    private String topic = "seckill-order-topic";
    private String consumerGroup = "seckill-consumer-group";
    private Reconciliation reconciliation = new Reconciliation();
    private Funnel funnel = new Funnel();
    private Consume consume = new Consume();
    private Token token = new Token();

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
        if (funnel == null) {
            throw new IllegalStateException("local-deals.seckill.funnel must not be null");
        }
        funnel.validate();
        if (consume == null) {
            throw new IllegalStateException("local-deals.seckill.consume must not be null");
        }
        consume.validate();
        if (token == null) {
            throw new IllegalStateException("local-deals.seckill.token must not be null");
        }
        token.validate();
    }

    /** The batch consumer: how much of the broker's backlog one round of work takes. */
    @Data
    public static class Consume {
        private static final int MAX_BATCH_SIZE = 1_000;

        /**
         * Master switch for the batch consumer. Spring tests that drive the processor directly
         * turn it off so no cached ApplicationContext competes for real broker messages.
         */
        private boolean enabled = true;
        /** Messages handed to one consume call; one INSERT and one stock update per voucher. */
        private int batchSize = 64;
        /** Consumer threads; keep at or below the DB pool so a thread never waits for a connection. */
        private int threadCount = 16;
        /**
         * How long a claimed batch is leased away from the reconciler. It must outlast one
         * persist round trip and is released as soon as the batch is finalized.
         */
        private Duration claimLease = Duration.ofSeconds(30);

        void validate() {
            if (batchSize < 1 || batchSize > MAX_BATCH_SIZE) {
                throw new IllegalStateException(
                        "local-deals.seckill.consume.batch-size must be between 1 and " + MAX_BATCH_SIZE);
            }
            if (threadCount < 1 || threadCount > 256) {
                throw new IllegalStateException(
                        "local-deals.seckill.consume.thread-count must be between 1 and 256");
            }
            if (claimLease == null || claimLease.getSeconds() < 5) {
                throw new IllegalStateException(
                        "local-deals.seckill.consume.claim-lease must be at least five seconds");
            }
        }
    }

    /** Short-lived purchase tokens, issued only while an activity is open. */
    @Data
    public static class Token {
        private boolean required = true;
        private String secret = "local-dev-seckill-token-secret";
        private Duration ttl = Duration.ofMinutes(10);

        void validate() {
            if (secret == null || secret.isBlank() || ttl == null || ttl.getSeconds() < 10) {
                throw new IllegalStateException("local-deals.seckill.token needs a secret and a ttl of at least 10s");
            }
        }
    }

    /** The JVM-local layers of the admission funnel. */
    @Data
    public static class Funnel {
        /** L2: permits per second per instance = remaining stock x this factor. */
        private double stockFactor = 2.0;
        /** L2: floor of that rate, so a nearly sold-out voucher still admits some traffic. */
        private double minPermitsPerSecond = 50;
        /** L1: a voucher flagged sold out lets one probe per interval through to Redis. */
        private Duration soldOutProbeInterval = Duration.ofSeconds(1);

        void validate() {
            if (!(stockFactor > 0) || !(minPermitsPerSecond >= 1) || soldOutProbeInterval == null
                    || soldOutProbeInterval.toMillis() < 10) {
                throw new IllegalStateException("local-deals.seckill.funnel settings are out of range");
            }
        }
    }

    @Data
    public static class Reconciliation {
        private static final int MAX_BATCH_SIZE = 1_000;

        /** Master switch for the scheduled reconciliation worker. */
        private boolean enabled = true;
        /** Timeout compensation keeps its own switch so an operator can pause stock release alone. */
        private boolean compensationEnabled = true;
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
