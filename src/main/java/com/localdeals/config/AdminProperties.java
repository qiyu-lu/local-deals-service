package com.localdeals.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.Arrays;

/**
 * Temporary fail-closed administrator boundary used until database-backed RBAC is introduced.
 */
@Component
@ConfigurationProperties(prefix = "local-deals.admin")
public class AdminProperties {

    private String userIds = "";

    public boolean isAdminUser(Long userId) {
        if (userId == null) {
            return false;
        }
        String expected = userId.toString();
        return Arrays.stream(split(userIds)).anyMatch(expected::equals);
    }

    private String[] split(String value) {
        if (value == null || value.trim().isEmpty()) {
            return new String[0];
        }
        return Arrays.stream(value.split(","))
                .map(String::trim)
                .filter(item -> !item.isEmpty())
                .toArray(String[]::new);
    }

    public String getUserIds() {
        return userIds;
    }

    public void setUserIds(String userIds) {
        this.userIds = userIds;
    }
}
