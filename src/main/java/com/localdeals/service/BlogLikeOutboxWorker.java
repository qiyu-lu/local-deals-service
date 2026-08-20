package com.localdeals.service;

import com.localdeals.config.BlogLikeProperties;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.Collections;

import static com.localdeals.utils.RedisConstants.BLOG_LIKE_OUTBOX_LOCK_KEY;

/** Reduces cross-instance contention while retaining MySQL as the correctness boundary. */
@Slf4j
@Service
public class BlogLikeOutboxWorker {

    private final BlogLikeProperties properties;
    private final BlogLikeOutboxBatchService batchService;
    private final RedissonClient redissonClient;

    public BlogLikeOutboxWorker(BlogLikeProperties properties,
                                BlogLikeOutboxBatchService batchService,
                                RedissonClient redissonClient) {
        this.properties = properties;
        this.batchService = batchService;
        this.redissonClient = redissonClient;
    }

    @Scheduled(
            initialDelayString = "#{@blogLikeProperties.initialDelay.toMillis()}",
            fixedDelayString = "#{@blogLikeProperties.fixedDelay.toMillis()}")
    public void scheduledFlush() {
        if (!properties.isWorkerEnabled()) {
            return;
        }
        try {
            flushOnce();
        } catch (RuntimeException e) {
            // The database transaction has rolled back and the outbox rows remain pending.
            log.error("Blog-like outbox batch failed and will be retried", e);
        }
    }

    public BlogLikeOutboxBatchService.BatchResult flushOnce() {
        RLock lock = null;
        boolean acquired = false;
        try {
            lock = redissonClient.getLock(BLOG_LIKE_OUTBOX_LOCK_KEY);
            acquired = lock.tryLock();
        } catch (RuntimeException redisFailure) {
            // Redisson is only a contention-reduction optimization. MySQL's SELECT ... FOR
            // UPDATE and the aggregate/marker transaction remain the correctness boundary.
            log.warn("Redis blog-like outbox lock unavailable; falling back to DB locking",
                    redisFailure);
            return batchService.processNextBatch(properties.getBatchSize());
        }
        if (!acquired) {
            return new BlogLikeOutboxBatchService.BatchResult(0, 0, Collections.emptyList());
        }
        try {
            return batchService.processNextBatch(properties.getBatchSize());
        } finally {
            try {
                if (lock.isHeldByCurrentThread()) {
                    lock.unlock();
                }
            } catch (RuntimeException unlockFailure) {
                log.warn("Unable to release the blog-like outbox load-shedding lock",
                        unlockFailure);
            }
        }
    }
}
