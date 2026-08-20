package com.localdeals.service;

import com.localdeals.config.BlogHotRankProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static com.localdeals.service.BlogHotRankReadResult.MissReason.BAD_MEMBER;
import static com.localdeals.service.BlogHotRankReadResult.MissReason.INCONSISTENT_SNAPSHOT;
import static com.localdeals.service.BlogHotRankReadResult.MissReason.NOT_READY;
import static com.localdeals.service.BlogHotRankReadResult.MissReason.OUTSIDE_TOP_K;
import static com.localdeals.service.BlogHotRankReadResult.MissReason.READ_DISABLED;
import static com.localdeals.service.BlogHotRankReadResult.MissReason.REDIS_UNAVAILABLE;
import static com.localdeals.service.BlogHotRankService.GENERATION_KEY;
import static com.localdeals.service.BlogHotRankService.GLOBAL_HASH_TAG;
import static com.localdeals.service.BlogHotRankService.LIVE_KEY;
import static com.localdeals.service.BlogHotRankService.LOCK_KEY;
import static com.localdeals.service.BlogHotRankService.META_KEY;
import static com.localdeals.service.BlogHotRankService.QUERY_TOP_BLOGS_SQL;
import static com.localdeals.service.BlogHotRankService.RebuildOutcome.PUBLISHED;
import static com.localdeals.service.BlogHotRankService.RebuildOutcome.SKIPPED_LOCK_BUSY;
import static com.localdeals.service.BlogHotRankService.RebuildOutcome.STALE_GENERATION;
import static com.localdeals.service.BlogHotRankService.TEMP_KEY_PREFIX;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Pure unit tests: MySQL, Redis and Redisson are all mocks. */
@ExtendWith(MockitoExtension.class)
class BlogHotRankServiceTest {

    @Mock
    private JdbcTemplate jdbcTemplate;
    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private RedissonClient redissonClient;
    @Mock
    private RLock lock;
    @Mock
    private ValueOperations<String, String> valueOperations;
    @Mock
    private HashOperations<String, Object, Object> hashOperations;
    @Mock
    private ZSetOperations<String, String> zSetOperations;

    private BlogHotRankProperties properties;
    private BlogHotRankService service;

    @BeforeEach
    void setUp() {
        properties = new BlogHotRankProperties();
        service = new BlogHotRankService(jdbcTemplate, redisTemplate, redissonClient, properties);
    }

    @Test
    void allAtomicAndLockKeysShareTheGlobalClusterHashTag() {
        assertThat(Arrays.asList(
                LIVE_KEY, META_KEY, GENERATION_KEY, TEMP_KEY_PREFIX, LOCK_KEY))
                .allMatch(key -> key.contains(GLOBAL_HASH_TAG));
    }

    @Test
    void disabledReadIsAnExplicitMissWithoutTouchingRedis() {
        BlogHotRankReadResult result = service.readPage(1);

        assertThat(result.isHit()).isFalse();
        assertThat(result.getMissReason()).isEqualTo(READ_DISABLED);
        verifyNoInteractions(redisTemplate);
    }

    @Test
    void unreadyRankIsAnExplicitMiss() {
        enableReads();
        when(redisTemplate.opsForHash()).thenReturn(hashOperations);
        when(hashOperations.get(META_KEY, "ready")).thenReturn(null);

        BlogHotRankReadResult result = service.readPage(1);

        assertThat(result.isHit()).isFalse();
        assertThat(result.getMissReason()).isEqualTo(NOT_READY);
        verify(redisTemplate, never()).opsForZSet();
    }

    @Test
    void pageStartingOutsideTopKIsAMissWithoutTouchingRedis() {
        enableReads();
        properties.setTopK(20);
        properties.setPageSize(10);

        BlogHotRankReadResult result = service.readPage(3);

        assertThat(result.isHit()).isFalse();
        assertThat(result.getMissReason()).isEqualTo(OUTSIDE_TOP_K);
        verifyNoInteractions(redisTemplate);
    }

    @Test
    void pageCrossingTheTopKBoundaryFallsBackAsAWholePage() {
        enableReads();
        properties.setTopK(25);
        properties.setPageSize(10);

        BlogHotRankReadResult result = service.readPage(3);

        assertThat(result.isHit()).isFalse();
        assertThat(result.getMissReason()).isEqualTo(OUTSIDE_TOP_K);
        verifyNoInteractions(redisTemplate);
    }

    @Test
    void readyRankReturnsStrictlyParsedIdsInRedisOrder() {
        enableReads();
        prepareReadyMetadata("7", "7");
        when(redisTemplate.opsForZSet()).thenReturn(zSetOperations);
        when(zSetOperations.reverseRange(LIVE_KEY, 0L, 9L)).thenReturn(linkedSet(
                "0000000000000000019",
                "0000000000000000002"));

        BlogHotRankReadResult result = service.readPage(1);

        assertThat(result.isHit()).isTrue();
        assertThat(result.getMissReason()).isNull();
        assertThat(result.getBlogIds()).containsExactly(19L, 2L);
    }

    @Test
    void malformedMemberMakesTheWholeReadMiss() {
        enableReads();
        prepareReadyMetadata("8", "8");
        when(redisTemplate.opsForZSet()).thenReturn(zSetOperations);
        when(zSetOperations.reverseRange(LIVE_KEY, 0L, 9L)).thenReturn(linkedSet(
                "0000000000000000019",
                "19"));

        BlogHotRankReadResult result = service.readPage(1);

        assertThat(result.isHit()).isFalse();
        assertThat(result.getMissReason()).isEqualTo(BAD_MEMBER);
        assertThat(result.getBlogIds()).isEmpty();
    }

    @Test
    void missingLiveRankCannotMasqueradeAsAReadyEmptyRank() {
        enableReads();
        prepareReadyMetadata("8", "8");
        when(redisTemplate.opsForZSet()).thenReturn(zSetOperations);
        when(zSetOperations.zCard(LIVE_KEY)).thenReturn(0L);

        BlogHotRankReadResult result = service.readPage(1);

        assertThat(result.isHit()).isFalse();
        assertThat(result.getMissReason()).isEqualTo(INCONSISTENT_SNAPSHOT);
    }

    @Test
    void generationChangeDuringReadMakesTheSnapshotMiss() {
        enableReads();
        prepareReadyMetadata("9", "10");
        when(redisTemplate.opsForZSet()).thenReturn(zSetOperations);
        when(zSetOperations.reverseRange(LIVE_KEY, 0L, 9L))
                .thenReturn(Collections.singleton("0000000000000000001"));

        BlogHotRankReadResult result = service.readPage(1);

        assertThat(result.isHit()).isFalse();
        assertThat(result.getMissReason()).isEqualTo(INCONSISTENT_SNAPSHOT);
    }

    @Test
    void redisFailureIsAnExplicitMiss() {
        enableReads();
        when(redisTemplate.opsForHash()).thenThrow(new IllegalStateException("redis unavailable"));

        BlogHotRankReadResult result = service.readPage(1);

        assertThat(result.isHit()).isFalse();
        assertThat(result.getMissReason()).isEqualTo(REDIS_UNAVAILABLE);
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void rebuildStagesPaddedMembersAndPublishesTheFencedGeneration() {
        prepareRefreshLock(true);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.increment(GENERATION_KEY)).thenReturn(17L);
        when(jdbcTemplate.query(eq(QUERY_TOP_BLOGS_SQL), any(RowMapper.class), eq(1_000)))
                .thenReturn(Arrays.asList(
                        new BlogHotRankService.Candidate(19L, 8L),
                        new BlogHotRankService.Candidate(2L, 8L)));
        when(redisTemplate.opsForZSet()).thenReturn(zSetOperations);
        when(zSetOperations.add(eq(TEMP_KEY_PREFIX + "17"), any(Set.class))).thenReturn(2L);
        when(redisTemplate.expire(TEMP_KEY_PREFIX + "17", properties.getMaxStale()))
                .thenReturn(true);
        when(redisTemplate.execute(
                any(RedisScript.class), anyList(), eq("17"), eq("2"), any(), eq("1000")))
                .thenReturn(1L);

        assertThat(service.rebuild()).isEqualTo(PUBLISHED);

        ArgumentCaptor<Set> tuplesCaptor = ArgumentCaptor.forClass(Set.class);
        verify(zSetOperations).add(eq(TEMP_KEY_PREFIX + "17"), tuplesCaptor.capture());
        verify(redisTemplate).expire(TEMP_KEY_PREFIX + "17", properties.getMaxStale());
        assertThat((Set<ZSetOperations.TypedTuple<String>>) tuplesCaptor.getValue())
                .extracting(ZSetOperations.TypedTuple::getValue)
                .containsExactly(
                        "0000000000000000019",
                        "0000000000000000002");
        verify(redisTemplate).execute(
                any(RedisScript.class),
                eq(Arrays.asList(LIVE_KEY, META_KEY, GENERATION_KEY, TEMP_KEY_PREFIX + "17")),
                eq("17"), eq("2"), any(), eq("1000"));
        verify(lock).unlock();
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void emptyDatabaseStillPublishesAReadyGenerationWithoutCreatingATemporaryZset() {
        prepareRefreshLock(true);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.increment(GENERATION_KEY)).thenReturn(18L);
        when(jdbcTemplate.query(eq(QUERY_TOP_BLOGS_SQL), any(RowMapper.class), eq(1_000)))
                .thenReturn(Collections.emptyList());
        when(redisTemplate.execute(
                any(RedisScript.class), anyList(), eq("18"), eq("0"), any(), eq("1000")))
                .thenReturn(1L);

        assertThat(service.rebuild()).isEqualTo(PUBLISHED);

        verify(redisTemplate, never()).opsForZSet();
        verify(redisTemplate).execute(
                any(RedisScript.class), anyList(), eq("18"), eq("0"), any(), eq("1000"));
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void olderBuilderCannotPublishAfterGenerationHasAdvanced() {
        prepareRefreshLock(true);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.increment(GENERATION_KEY)).thenReturn(19L);
        when(jdbcTemplate.query(eq(QUERY_TOP_BLOGS_SQL), any(RowMapper.class), eq(1_000)))
                .thenReturn(Collections.emptyList());
        when(redisTemplate.execute(
                any(RedisScript.class), anyList(), eq("19"), eq("0"), any(), eq("1000")))
                .thenReturn(0L);

        assertThat(service.rebuild()).isEqualTo(STALE_GENERATION);
    }

    @Test
    void busyRefreshLockShedsDuplicateDatabaseWork() {
        prepareRefreshLock(false);

        assertThat(service.rebuild()).isEqualTo(SKIPPED_LOCK_BUSY);

        verifyNoInteractions(jdbcTemplate);
        verify(redisTemplate, never()).opsForValue();
        verify(lock, never()).unlock();
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void afterCommitHookUsesOneSameSlotScriptForReadyNxInsertAndBoundedTrim() {
        when(redisTemplate.execute(
                any(RedisScript.class), anyList(), eq("0000000000000000023"), eq("1000")))
                .thenReturn(1L);

        service.addNewBlogAfterCommit(23L);

        verify(redisTemplate).execute(
                any(RedisScript.class),
                eq(Arrays.asList(LIVE_KEY, META_KEY, GENERATION_KEY)),
                eq("0000000000000000023"), eq("1000"));
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void afterCommitHookDelegatesTheNotReadyDecisionToTheAtomicScript() {
        when(redisTemplate.execute(
                any(RedisScript.class), anyList(), eq("0000000000000000024"), eq("1000")))
                .thenReturn(0L);

        service.addNewBlogAfterCommit(24L);

        verify(redisTemplate).execute(
                any(RedisScript.class),
                eq(Arrays.asList(LIVE_KEY, META_KEY, GENERATION_KEY)),
                eq("0000000000000000024"), eq("1000"));
    }

    @Test
    void afterCommitRedisFailureNeverEscapes() {
        when(redisTemplate.execute(
                any(RedisScript.class), anyList(), eq("0000000000000000025"), eq("1000")))
                .thenThrow(new IllegalStateException("redis unavailable"));

        assertThatCode(() -> service.addNewBlogAfterCommit(25L)).doesNotThrowAnyException();
    }

    private void enableReads() {
        properties.setReadEnabled(true);
    }

    private void prepareReadyMetadata(String generationBefore, String generationAfter) {
        when(redisTemplate.opsForHash()).thenReturn(hashOperations);
        when(hashOperations.get(META_KEY, "ready")).thenReturn("1");
        when(hashOperations.get(META_KEY, "generation"))
                .thenReturn(generationBefore, generationAfter);
        when(hashOperations.get(META_KEY, "count")).thenReturn("2", "2");
        when(hashOperations.get(META_KEY, "capacity")).thenReturn("1000");
        when(hashOperations.get(META_KEY, "publishedAt"))
                .thenReturn(Long.toString(System.currentTimeMillis()));
        when(redisTemplate.opsForZSet()).thenReturn(zSetOperations);
        when(zSetOperations.zCard(LIVE_KEY)).thenReturn(2L);
    }

    private void prepareRefreshLock(boolean acquired) {
        when(redissonClient.getLock(LOCK_KEY)).thenReturn(lock);
        when(lock.tryLock()).thenReturn(acquired);
        if (acquired) {
            when(lock.isHeldByCurrentThread()).thenReturn(true);
        }
    }

    private static LinkedHashSet<String> linkedSet(String... members) {
        return new LinkedHashSet<>(Arrays.asList(members));
    }
}
