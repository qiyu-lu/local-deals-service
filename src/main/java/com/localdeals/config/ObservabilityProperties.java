package com.localdeals.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.time.Duration;

/** Bounded configuration for low-frequency reliability sampling. */
@Data
@Component
@ConfigurationProperties(prefix = "local-deals.observability")
public class ObservabilityProperties {

    private static final Duration MINIMUM_SAMPLING_INTERVAL = Duration.ofSeconds(15);

    /** Sampling is independent from business workers and is safe to leave disabled. */
    private boolean samplingEnabled = false;
    private Duration initialDelay = Duration.ofSeconds(10);
    private Duration samplingInterval = Duration.ofSeconds(30);

    @PostConstruct
    public void validate() {
        requirePositive(initialDelay, "initial-delay");
        requirePositive(samplingInterval, "sampling-interval");
        if (samplingInterval.compareTo(MINIMUM_SAMPLING_INTERVAL) < 0) {
            throw new IllegalStateException(
                    "local-deals.observability.sampling-interval must be at least 15s");
        }
    }

    private static void requirePositive(Duration value, String name) {
        if (value == null || value.isZero() || value.isNegative() || value.toMillis() <= 0L) {
            throw new IllegalStateException(
                    "local-deals.observability." + name + " must be positive");
        }
    }
}
