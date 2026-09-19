package com.localdeals.trade.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.time.Duration;

@Data
@Component
@ConfigurationProperties(prefix = "local-deals.payment")
public class PaymentProperties {

    /** Shared HMAC-SHA256 key between us and the (mock) channel. */
    private String callbackSecret;
    /** Refunds the channel has not confirmed after this long are requested again by the scan. */
    private Duration refundRetryAfter = Duration.ofMinutes(1);
    private MockChannel mockChannel = new MockChannel();

    @PostConstruct
    public void validate() {
        if (callbackSecret == null || callbackSecret.length() < 16) {
            throw new IllegalStateException("local-deals.payment.callback-secret must be at least 16 characters");
        }
        if (refundRetryAfter == null || refundRetryAfter.isNegative()) {
            throw new IllegalStateException("local-deals.payment.refund-retry-after must not be negative");
        }
        mockChannel.validate();
    }

    @Data
    public static class MockChannel {
        /** Base URL the mock channel calls back, i.e. this application. */
        private String notifyBaseUrl = "http://127.0.0.1:8083";
        /** Every notification is delivered this many times, like a real channel's at-least-once. */
        private int duplicates = 2;
        private int maxAttempts = 8;
        private Duration initialBackoff = Duration.ofSeconds(1);
        /** Random extra delay per delivery, so callbacks arrive out of order. */
        private Duration jitter = Duration.ofMillis(300);

        void validate() {
            if (duplicates < 1 || maxAttempts < 1 || initialBackoff == null || initialBackoff.isNegative() ||
                    jitter == null || jitter.isNegative()) {
                throw new IllegalStateException("local-deals.payment.mock-channel settings are out of range");
            }
        }
    }
}
