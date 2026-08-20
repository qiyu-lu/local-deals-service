package com.localdeals.service;

import com.localdeals.config.BlogHotRankProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Configuration-gated scheduler for the Redis-derived blog hot rank. */
@Component
@ConditionalOnProperty(
        prefix = "local-deals.blog-hot-rank",
        name = "refresh-enabled",
        havingValue = "true")
public class BlogHotRankRefreshScheduler {

    private final BlogHotRankService hotRankService;
    private final BlogHotRankProperties properties;

    public BlogHotRankRefreshScheduler(BlogHotRankService hotRankService,
                                       BlogHotRankProperties properties) {
        this.hotRankService = hotRankService;
        this.properties = properties;
    }

    @Scheduled(
            initialDelayString = "#{@blogHotRankProperties.initialDelay.toMillis()}",
            fixedDelayString = "#{@blogHotRankProperties.fixedDelay.toMillis()}")
    public void rebuildIfEnabled() {
        if (!properties.isRefreshEnabled()) {
            return;
        }
        hotRankService.rebuild();
    }
}
