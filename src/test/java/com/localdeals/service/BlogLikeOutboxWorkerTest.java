package com.localdeals.service;

import com.localdeals.config.BlogLikeProperties;
import com.localdeals.observability.LocalDealsMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;

import java.util.Collections;

import static com.localdeals.utils.RedisConstants.BLOG_LIKE_OUTBOX_LOCK_KEY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BlogLikeOutboxWorkerTest {

    private BlogLikeProperties properties;
    private BlogLikeOutboxBatchService batchService;
    private RedissonClient redissonClient;
    private RLock lock;
    private BlogLikeOutboxWorker worker;

    @BeforeEach
    void setUp() {
        properties = new BlogLikeProperties();
        properties.setBatchSize(25);
        batchService = mock(BlogLikeOutboxBatchService.class);
        redissonClient = mock(RedissonClient.class);
        lock = mock(RLock.class);
        when(redissonClient.getLock(BLOG_LIKE_OUTBOX_LOCK_KEY)).thenReturn(lock);
        worker = new BlogLikeOutboxWorker(properties, batchService, redissonClient,
                new LocalDealsMetrics(new SimpleMeterRegistry()));
    }

    @Test
    void lockLoserDoesNotTouchDatabaseBatch() {
        when(lock.tryLock()).thenReturn(false);

        BlogLikeOutboxBatchService.BatchResult result = worker.flushOnce();

        assertThat(result.getProcessedEvents()).isZero();
        verify(batchService, never()).processNextBatch(25);
        verify(lock, never()).unlock();
    }

    @Test
    void lockWinnerProcessesThroughTransactionalCollaboratorThenUnlocks() {
        BlogLikeOutboxBatchService.BatchResult expected =
                new BlogLikeOutboxBatchService.BatchResult(2, 1, Collections.singletonList(8L));
        when(lock.tryLock()).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);
        when(batchService.processNextBatch(25)).thenReturn(expected);

        assertThat(worker.flushOnce()).isSameAs(expected);

        verify(batchService).processNextBatch(25);
        verify(lock).unlock();
    }

    @Test
    void disabledSchedulerDoesNotAcquireDistributedLock() {
        properties.setWorkerEnabled(false);

        worker.scheduledFlush();

        verify(batchService, never()).processNextBatch(25);
        verify(lock, never()).tryLock();
    }

    @Test
    void redisLockFailureFallsBackToTransactionalDatabaseBatch() {
        BlogLikeOutboxBatchService.BatchResult expected =
                new BlogLikeOutboxBatchService.BatchResult(1, 1, Collections.singletonList(9L));
        when(redissonClient.getLock(BLOG_LIKE_OUTBOX_LOCK_KEY))
                .thenThrow(new IllegalStateException("redis unavailable"));
        when(batchService.processNextBatch(25)).thenReturn(expected);

        assertThat(worker.flushOnce()).isSameAs(expected);

        verify(batchService).processNextBatch(25);
    }
}
