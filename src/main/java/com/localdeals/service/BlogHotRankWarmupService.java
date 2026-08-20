package com.localdeals.service;

import com.localdeals.config.BlogHotRankProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

/** Local single-flight trigger; the rebuild's Redisson lock provides the cluster-wide boundary. */
@Slf4j
@Service
public class BlogHotRankWarmupService {

    private final BlogHotRankService hotRankService;
    private final BlogHotRankProperties properties;
    private final Executor executor;
    private final AtomicBoolean inFlight = new AtomicBoolean(false);

    public BlogHotRankWarmupService(BlogHotRankService hotRankService,
                                    BlogHotRankProperties properties,
                                    @Qualifier("applicationTaskExecutor") Executor executor) {
        this.hotRankService = hotRankService;
        this.properties = properties;
        this.executor = executor;
    }

    public void triggerIfEnabled() {
        if (!properties.isRefreshEnabled() || !inFlight.compareAndSet(false, true)) {
            return;
        }
        try {
            executor.execute(() -> {
                try {
                    hotRankService.rebuild();
                } catch (RuntimeException e) {
                    log.warn("Asynchronous blog hot-rank warmup failed", e);
                } finally {
                    inFlight.set(false);
                }
            });
        } catch (RuntimeException rejected) {
            inFlight.set(false);
            log.warn("Blog hot-rank warmup executor rejected the task", rejected);
        }
    }

    boolean isInFlight() {
        return inFlight.get();
    }
}
