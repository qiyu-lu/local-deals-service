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

    @PostConstruct
    public void validate() {
        if (payTimeout == null || payTimeout.getSeconds() < 1) {
            throw new IllegalStateException("local-deals.order.pay-timeout must be at least one second");
        }
    }
}
