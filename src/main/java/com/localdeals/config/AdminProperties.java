package com.localdeals.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.convert.DurationUnit;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * Configuration for the independent merchant administration identity boundary.
 */
@Component
@ConfigurationProperties(prefix = "local-deals.admin")
public class AdminProperties {

    private final Bootstrap bootstrap = new Bootstrap();

    @DurationUnit(ChronoUnit.MINUTES)
    private Duration sessionTtl = Duration.ofMinutes(30);

    private int maxLoginFailures = 5;

    private int maxIpLoginAttempts = 30;

    private List<String> trustedProxies = new ArrayList<>();

    @DurationUnit(ChronoUnit.MINUTES)
    private Duration loginLockDuration = Duration.ofMinutes(15);

    public Bootstrap getBootstrap() {
        return bootstrap;
    }

    public Duration getSessionTtl() {
        return sessionTtl;
    }

    public void setSessionTtl(Duration sessionTtl) {
        this.sessionTtl = sessionTtl;
    }

    public int getMaxLoginFailures() {
        return maxLoginFailures;
    }

    public void setMaxLoginFailures(int maxLoginFailures) {
        this.maxLoginFailures = maxLoginFailures;
    }

    public int getMaxIpLoginAttempts() {
        return maxIpLoginAttempts;
    }

    public void setMaxIpLoginAttempts(int maxIpLoginAttempts) {
        this.maxIpLoginAttempts = maxIpLoginAttempts;
    }

    public List<String> getTrustedProxies() {
        return trustedProxies;
    }

    public void setTrustedProxies(List<String> trustedProxies) {
        this.trustedProxies = trustedProxies == null ? new ArrayList<>() : trustedProxies;
    }

    public Duration getLoginLockDuration() {
        return loginLockDuration;
    }

    public void setLoginLockDuration(Duration loginLockDuration) {
        this.loginLockDuration = loginLockDuration;
    }

    public static class Bootstrap {
        private String username = "";
        private String password = "";
        private String displayName = "平台管理员";

        public String getUsername() {
            return username;
        }

        public void setUsername(String username) {
            this.username = username;
        }

        public String getPassword() {
            return password;
        }

        public void setPassword(String password) {
            this.password = password;
        }

        public String getDisplayName() {
            return displayName;
        }

        public void setDisplayName(String displayName) {
            this.displayName = displayName;
        }
    }
}
