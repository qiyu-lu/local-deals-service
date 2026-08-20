package com.localdeals.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.time.Duration;

/**
 * Fail-closed rollout controls and bounded sizing for the Redis-derived blog hot rank.
 */
@Component
@ConfigurationProperties(prefix = "local-deals.blog-hot-rank")
public class BlogHotRankProperties {

    private static final int MAX_TOP_K = 100_000;
    private static final int MAX_PAGE_SIZE = 100;

    private boolean readEnabled = false;
    private boolean refreshEnabled = false;
    private int topK = 1_000;
    private int pageSize = 10;
    private Duration initialDelay = Duration.ofSeconds(10);
    private Duration fixedDelay = Duration.ofSeconds(30);
    private Duration maxStale = Duration.ofMinutes(2);

    @PostConstruct
    public void validate() {
        if (topK <= 0 || topK > MAX_TOP_K) {
            throw new IllegalStateException(
                    "local-deals.blog-hot-rank.top-k must be between 1 and " + MAX_TOP_K);
        }
        if (pageSize <= 0 || pageSize > topK || pageSize > MAX_PAGE_SIZE) {
            throw new IllegalStateException(
                    "local-deals.blog-hot-rank.page-size must be between 1 and " +
                            "min(top-k, " + MAX_PAGE_SIZE + ")");
        }
        requirePositive(initialDelay, "initial-delay");
        requirePositive(fixedDelay, "fixed-delay");
        requirePositive(maxStale, "max-stale");
        if (maxStale.compareTo(fixedDelay) <= 0) {
            throw new IllegalStateException(
                    "local-deals.blog-hot-rank.max-stale must be greater than fixed-delay");
        }
    }

    private static void requirePositive(Duration value, String property) {
        if (value == null || value.isZero() || value.isNegative() || value.toMillis() <= 0L) {
            throw new IllegalStateException(
                    "local-deals.blog-hot-rank." + property + " must be positive");
        }
    }

    public boolean isReadEnabled() {
        return readEnabled;
    }

    public void setReadEnabled(boolean readEnabled) {
        this.readEnabled = readEnabled;
    }

    public boolean isRefreshEnabled() {
        return refreshEnabled;
    }

    public void setRefreshEnabled(boolean refreshEnabled) {
        this.refreshEnabled = refreshEnabled;
    }

    public int getTopK() {
        return topK;
    }

    public void setTopK(int topK) {
        this.topK = topK;
    }

    public int getPageSize() {
        return pageSize;
    }

    public void setPageSize(int pageSize) {
        this.pageSize = pageSize;
    }

    public Duration getInitialDelay() {
        return initialDelay;
    }

    public void setInitialDelay(Duration initialDelay) {
        this.initialDelay = initialDelay;
    }

    public Duration getFixedDelay() {
        return fixedDelay;
    }

    public void setFixedDelay(Duration fixedDelay) {
        this.fixedDelay = fixedDelay;
    }

    public Duration getMaxStale() {
        return maxStale;
    }

    public void setMaxStale(Duration maxStale) {
        this.maxStale = maxStale;
    }
}
