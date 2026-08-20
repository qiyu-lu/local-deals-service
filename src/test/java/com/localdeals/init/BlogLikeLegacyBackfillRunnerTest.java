package com.localdeals.init;

import com.localdeals.config.BlogLikeProperties;
import com.localdeals.service.BlogLikeLegacyImportService;
import com.localdeals.service.BlogLikeCutoverService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RKeys;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.DefaultTypedTuple;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BlogLikeLegacyBackfillRunnerTest {

    private RKeys keys;
    private ZSetOperations<String, String> zSet;
    private BlogLikeLegacyImportService importService;
    private BlogLikeProperties properties;
    private BlogLikeLegacyBackfillRunner runner;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        RedissonClient redissonClient = mock(RedissonClient.class);
        keys = mock(RKeys.class);
        when(redissonClient.getKeys()).thenReturn(keys);
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        zSet = mock(ZSetOperations.class);
        when(redisTemplate.opsForZSet()).thenReturn(zSet);
        importService = mock(BlogLikeLegacyImportService.class);
        properties = new BlogLikeProperties();
        properties.setWorkerEnabled(false);
        properties.setLegacyBackfillOnStartup(true);
        properties.setLegacyBatchSize(2);
        properties.setLegacyScanCount(50);
        runner = new BlogLikeLegacyBackfillRunner(
                redissonClient, redisTemplate, importService, properties,
                mock(BlogLikeCutoverService.class));
    }

    @Test
    void scansCanonicalKeysInBoundedPagesAndDelegatesTransactionalImport() {
        when(keys.getKeysByPattern("blog:liked:*", 50))
                .thenReturn(Collections.singletonList("blog:liked:41"));
        when(zSet.zCard("blog:liked:41")).thenReturn(2L, 2L);
        Set<ZSetOperations.TypedTuple<String>> page = new LinkedHashSet<>(Arrays.asList(
                new DefaultTypedTuple<>("7", 1_000D),
                new DefaultTypedTuple<>("9", 2_000D)));
        when(zSet.rangeWithScores("blog:liked:41", 0L, 1L)).thenReturn(page);
        when(zSet.rangeWithScores("blog:liked:41", 2L, 3L)).thenReturn(Collections.emptySet());
        when(importService.importBatch(41L, Arrays.asList(
                new BlogLikeLegacyImportService.LegacyLike(7L, 1_000L),
                new BlogLikeLegacyImportService.LegacyLike(9L, 2_000L))))
                .thenReturn(new BlogLikeLegacyImportService.ImportResult(2, 2, 3, 2));

        BlogLikeLegacyBackfillRunner.BackfillSummary summary = runner.backfillOnce();

        assertThat(summary.getKeys()).isEqualTo(1);
        assertThat(summary.getScanned()).isEqualTo(2);
        assertThat(summary.getInserted()).isEqualTo(2);
    }

    @Test
    void nonCanonicalKeyFailsClosedBeforeDatabaseMutation() {
        when(keys.getKeysByPattern("blog:liked:*", 50))
                .thenReturn(Collections.singletonList("blog:liked:01"));

        assertThatThrownBy(runner::backfillOnce)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Invalid legacy");
        verify(importService, never()).importBatch(
                org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyList());
    }
}
