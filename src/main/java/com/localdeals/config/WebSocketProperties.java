package com.localdeals.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Security-related configuration for the WebSocket endpoints.
 */
@Component
@ConfigurationProperties(prefix = "local-deals.websocket")
public class WebSocketProperties {

    private String allowedOrigins = "http://localhost:8088,http://localhost:5173";

    public String[] allowedOrigins() {
        return split(allowedOrigins);
    }

    private String[] split(String value) {
        if (value == null || value.trim().isEmpty()) {
            return new String[0];
        }
        return java.util.Arrays.stream(value.split(","))
                .map(String::trim)
                .filter(item -> !item.isEmpty())
                .toArray(String[]::new);
    }

    public String getAllowedOrigins() {
        return allowedOrigins;
    }

    public void setAllowedOrigins(String allowedOrigins) {
        this.allowedOrigins = allowedOrigins;
    }
}
