package com.localdeals.service;

import com.localdeals.config.BlogHotRankProperties;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.Executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class BlogHotRankWarmupServiceTest {

    @Test
    void disabledRefreshDoesNotScheduleWork() {
        BlogHotRankService rank = mock(BlogHotRankService.class);
        BlogHotRankProperties properties = new BlogHotRankProperties();
        QueueExecutor executor = new QueueExecutor();

        new BlogHotRankWarmupService(rank, properties, executor).triggerIfEnabled();

        assertThat(executor.tasks).isEmpty();
        verify(rank, never()).rebuild();
    }

    @Test
    void concurrentMissesCoalesceUntilTheScheduledBuildCompletes() {
        BlogHotRankService rank = mock(BlogHotRankService.class);
        BlogHotRankProperties properties = new BlogHotRankProperties();
        properties.setRefreshEnabled(true);
        QueueExecutor executor = new QueueExecutor();
        BlogHotRankWarmupService warmup =
                new BlogHotRankWarmupService(rank, properties, executor);

        warmup.triggerIfEnabled();
        warmup.triggerIfEnabled();

        assertThat(executor.tasks).hasSize(1);
        assertThat(warmup.isInFlight()).isTrue();
        executor.runNext();
        assertThat(warmup.isInFlight()).isFalse();
        verify(rank).rebuild();

        warmup.triggerIfEnabled();
        executor.runNext();
        verify(rank, times(2)).rebuild();
    }

    private static final class QueueExecutor implements Executor {
        private final Queue<Runnable> tasks = new ArrayDeque<>();

        @Override
        public void execute(Runnable command) {
            tasks.add(command);
        }

        void runNext() {
            tasks.remove().run();
        }
    }
}
