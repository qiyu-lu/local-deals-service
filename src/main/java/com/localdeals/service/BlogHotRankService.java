package com.localdeals.service;

import com.localdeals.config.BlogHotRankProperties;
import com.localdeals.observability.LocalDealsMetrics;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.DefaultTypedTuple;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import static com.localdeals.service.BlogHotRankReadResult.MissReason.BAD_MEMBER;
import static com.localdeals.service.BlogHotRankReadResult.MissReason.BAD_METADATA;
import static com.localdeals.service.BlogHotRankReadResult.MissReason.INCONSISTENT_SNAPSHOT;
import static com.localdeals.service.BlogHotRankReadResult.MissReason.INVALID_PAGE;
import static com.localdeals.service.BlogHotRankReadResult.MissReason.NOT_READY;
import static com.localdeals.service.BlogHotRankReadResult.MissReason.OUTSIDE_TOP_K;
import static com.localdeals.service.BlogHotRankReadResult.MissReason.READ_DISABLED;
import static com.localdeals.service.BlogHotRankReadResult.MissReason.REDIS_UNAVAILABLE;
import static com.localdeals.service.BlogHotRankReadResult.MissReason.STALE;

/**
 * Bounded Redis read model derived from {@code tb_blog(liked, id)}.
 *
 * <p>MySQL remains authoritative. A builder writes a generation-specific temporary ZSET and a
 * Lua script publishes it only while that generation is still current. Readers get an explicit
 * miss for every unsafe state so the caller can fall back to MySQL.</p>
 */
@Slf4j
@Service
public class BlogHotRankService {

    private static final long REDIS_WARNING_INTERVAL_MILLIS = 30_000L;

    static final String GLOBAL_HASH_TAG = "{global}";
    static final String LIVE_KEY = "blog:hot:" + GLOBAL_HASH_TAG + ":live";
    static final String META_KEY = "blog:hot:" + GLOBAL_HASH_TAG + ":meta";
    static final String GENERATION_KEY = "blog:hot:" + GLOBAL_HASH_TAG + ":generation";
    static final String TEMP_KEY_PREFIX = "blog:hot:" + GLOBAL_HASH_TAG + ":temp:";
    static final String LOCK_KEY = "blog:hot:" + GLOBAL_HASH_TAG + ":lock";

    static final String QUERY_TOP_BLOGS_SQL =
            "SELECT id, COALESCE(liked, 0) AS liked " +
                    "FROM tb_blog ORDER BY liked DESC, id DESC LIMIT ?";

    private static final String META_READY_FIELD = "ready";
    private static final String META_GENERATION_FIELD = "generation";
    private static final String META_COUNT_FIELD = "count";
    private static final String META_CAPACITY_FIELD = "capacity";
    private static final String META_PUBLISHED_AT_FIELD = "publishedAt";

    private static final DefaultRedisScript<Long> PUBLISH_SCRIPT;
    private static final DefaultRedisScript<Long> ADD_NEW_BLOG_SCRIPT;

    static {
        PUBLISH_SCRIPT = new DefaultRedisScript<>();
        PUBLISH_SCRIPT.setLocation(new ClassPathResource("lua/blog_hot_rank_publish.lua"));
        PUBLISH_SCRIPT.setResultType(Long.class);
        ADD_NEW_BLOG_SCRIPT = new DefaultRedisScript<>();
        ADD_NEW_BLOG_SCRIPT.setLocation(new ClassPathResource("lua/blog_hot_rank_add_new.lua"));
        ADD_NEW_BLOG_SCRIPT.setResultType(Long.class);
    }

    private final JdbcTemplate jdbcTemplate;
    private final StringRedisTemplate redisTemplate;
    private final RedissonClient redissonClient;
    private final BlogHotRankProperties properties;
    private final LocalDealsMetrics metrics;
    private final AtomicLong nextRedisReadWarningAt = new AtomicLong(0L);

    public BlogHotRankService(JdbcTemplate jdbcTemplate,
                              StringRedisTemplate redisTemplate,
                              RedissonClient redissonClient,
                              BlogHotRankProperties properties,
                              LocalDealsMetrics metrics) {
        this.jdbcTemplate = jdbcTemplate;
        this.redisTemplate = redisTemplate;
        this.redissonClient = redissonClient;
        this.properties = properties;
        this.metrics = metrics;
    }

    /**
     * Reads one bounded page from the derived rank. Every unsafe condition is an explicit miss.
     */
    public BlogHotRankReadResult readPage(Integer page) {
        if (!properties.isReadEnabled()) {
            return observed(BlogHotRankReadResult.miss(READ_DISABLED));
        }
        if (page == null || page <= 0) {
            return observed(BlogHotRankReadResult.miss(INVALID_PAGE));
        }

        long offset = ((long) page - 1L) * properties.getPageSize();
        long requestedEndExclusive = offset + properties.getPageSize();
        if (offset >= properties.getTopK() || requestedEndExclusive > properties.getTopK()) {
            return observed(BlogHotRankReadResult.miss(OUTSIDE_TOP_K));
        }
        long end = requestedEndExclusive - 1L;

        try {
            HashOperations<String, Object, Object> hash = redisTemplate.opsForHash();
            Object ready = hash.get(META_KEY, META_READY_FIELD);
            if (!"1".equals(stringValue(ready))) {
                return observed(BlogHotRankReadResult.miss(NOT_READY));
            }

            String generationBefore = positiveDecimal(hash.get(META_KEY, META_GENERATION_FIELD));
            if (generationBefore == null) {
                return observed(BlogHotRankReadResult.miss(BAD_METADATA));
            }
            String countBefore = nonNegativeDecimal(hash.get(META_KEY, META_COUNT_FIELD));
            if (countBefore == null) {
                return observed(BlogHotRankReadResult.miss(BAD_METADATA));
            }
            String capacity = positiveDecimal(hash.get(META_KEY, META_CAPACITY_FIELD));
            String publishedAt = positiveDecimal(hash.get(META_KEY, META_PUBLISHED_AT_FIELD));
            if (capacity == null || publishedAt == null ||
                    Long.parseLong(capacity) != properties.getTopK()) {
                return observed(BlogHotRankReadResult.miss(BAD_METADATA));
            }
            long publishedAtMillis = Long.parseLong(publishedAt);
            long now = System.currentTimeMillis();
            if (publishedAtMillis > now || now - publishedAtMillis > properties.getMaxStale().toMillis()) {
                return observed(BlogHotRankReadResult.miss(STALE));
            }

            ZSetOperations<String, String> rank = redisTemplate.opsForZSet();
            Long cardinality = rank.zCard(LIVE_KEY);
            long expectedCount = Long.parseLong(countBefore);
            if (cardinality == null || cardinality != expectedCount ||
                    expectedCount > properties.getTopK()) {
                return observed(BlogHotRankReadResult.miss(INCONSISTENT_SNAPSHOT));
            }
            Set<String> members = rank.reverseRange(LIVE_KEY, offset, end);
            if (members == null) {
                return observed(BlogHotRankReadResult.miss(INCONSISTENT_SNAPSHOT));
            }

            String generationAfter = positiveDecimal(hash.get(META_KEY, META_GENERATION_FIELD));
            String countAfter = nonNegativeDecimal(hash.get(META_KEY, META_COUNT_FIELD));
            if (!generationBefore.equals(generationAfter) || !countBefore.equals(countAfter)) {
                return observed(BlogHotRankReadResult.miss(INCONSISTENT_SNAPSHOT));
            }

            List<Long> blogIds = new ArrayList<>(members.size());
            for (String member : members) {
                Long blogId = parseMember(member);
                if (blogId == null) {
                    return observed(BlogHotRankReadResult.miss(BAD_MEMBER));
                }
                blogIds.add(blogId);
            }
            return observed(BlogHotRankReadResult.hit(blogIds));
        } catch (RuntimeException e) {
            logRedisReadFailure(page, e);
            return observed(BlogHotRankReadResult.miss(REDIS_UNAVAILABLE));
        }
    }

    private BlogHotRankReadResult observed(BlogHotRankReadResult result) {
        metrics.recordHotRankRead(result);
        return result;
    }

    private void logRedisReadFailure(Integer page, RuntimeException failure) {
        long now = System.currentTimeMillis();
        long next = nextRedisReadWarningAt.get();
        if (now >= next && nextRedisReadWarningAt.compareAndSet(
                next, now + REDIS_WARNING_INTERVAL_MILLIS)) {
            log.warn("Redis blog hot-rank read failed; caller must use the DB fallback. page={}",
                    page, failure);
        } else {
            log.debug("Redis blog hot-rank read still unavailable. page={}", page);
        }
    }

    /**
     * Builds and publishes one complete top-K generation. The no-wait lock sheds duplicate work.
     */
    public RebuildOutcome rebuild() {
        long startedAt = System.nanoTime();
        RLock lock;
        try {
            lock = redissonClient.getLock(LOCK_KEY);
        } catch (RuntimeException e) {
            log.error("Unable to obtain the blog hot-rank lock handle", e);
            return observedRebuild(RebuildOutcome.FAILED, startedAt);
        }

        boolean locked = false;
        String temporaryKey = null;
        try {
            locked = lock.tryLock();
            if (!locked) {
                return observedRebuild(RebuildOutcome.SKIPPED_LOCK_BUSY, startedAt);
            }

            Long generation = redisTemplate.opsForValue().increment(GENERATION_KEY);
            if (generation == null || generation <= 0L) {
                throw new IllegalStateException("Redis returned an invalid blog hot-rank generation");
            }
            temporaryKey = temporaryKey(generation);

            List<Candidate> candidates = loadCandidates();
            stageCandidates(temporaryKey, candidates);

            Long published = redisTemplate.execute(
                    PUBLISH_SCRIPT,
                    Arrays.asList(LIVE_KEY, META_KEY, GENERATION_KEY, temporaryKey),
                    generation.toString(),
                    Integer.toString(candidates.size()),
                    Long.toString(System.currentTimeMillis()),
                    Integer.toString(properties.getTopK()));
            if (Long.valueOf(1L).equals(published)) {
                log.info("Published Redis blog hot rank. generation={}, size={}",
                        generation, candidates.size());
                return observedRebuild(RebuildOutcome.PUBLISHED, startedAt);
            }
            if (Long.valueOf(0L).equals(published)) {
                log.info("Discarded stale Redis blog hot-rank builder. generation={}", generation);
                return observedRebuild(RebuildOutcome.STALE_GENERATION, startedAt);
            }
            throw new IllegalStateException(
                    "Redis rejected the staged blog hot rank. generation=" + generation +
                            ", result=" + published);
        } catch (RuntimeException e) {
            log.error("Failed to rebuild the Redis blog hot rank", e);
            cleanupTemporaryKey(temporaryKey);
            return observedRebuild(RebuildOutcome.FAILED, startedAt);
        } finally {
            if (locked) {
                unlockBestEffort(lock);
            }
        }
    }

    private RebuildOutcome observedRebuild(RebuildOutcome outcome, long startedAt) {
        metrics.recordHotRankRebuild(outcome, System.nanoTime() - startedAt);
        return outcome;
    }

    /** Returns the age of valid published metadata; missing or invalid metadata is unavailable. */
    public double publishedAgeSeconds() {
        Object raw = redisTemplate.opsForHash().get(META_KEY, META_PUBLISHED_AT_FIELD);
        String publishedAt = positiveDecimal(raw);
        if (publishedAt == null) {
            throw new IllegalStateException("Blog hot-rank publication metadata is unavailable");
        }
        long publishedAtMillis = Long.parseLong(publishedAt);
        long now = System.currentTimeMillis();
        if (publishedAtMillis > now) {
            throw new IllegalStateException("Blog hot-rank publication time is in the future");
        }
        return (now - publishedAtMillis) / 1000D;
    }

    /**
     * Intended to be called by the blog-create transaction's {@code afterCommit} callback.
     * It never changes readiness and never leaks a Redis failure to the caller. Lua performs the
     * ready check, NX insertion, top-K trim, and generation/count fencing atomically in one slot.
     */
    public void addNewBlogAfterCommit(long blogId) {
        if (blogId <= 0L) {
            log.warn("Ignoring non-positive blog id in hot-rank after-commit hook. blogId={}", blogId);
            return;
        }
        try {
            redisTemplate.execute(
                    ADD_NEW_BLOG_SCRIPT,
                    Arrays.asList(LIVE_KEY, META_KEY, GENERATION_KEY),
                    formatMember(blogId),
                    Integer.toString(properties.getTopK()));
        } catch (RuntimeException e) {
            log.error("Failed to add a newly committed blog to the derived hot rank. blogId={}",
                    blogId, e);
        }
    }

    private List<Candidate> loadCandidates() {
        return jdbcTemplate.query(
                QUERY_TOP_BLOGS_SQL,
                (rs, rowNum) -> {
                    long blogId = rs.getLong("id");
                    long liked = rs.getLong("liked");
                    if (blogId <= 0L || liked < 0L) {
                        throw new IllegalStateException(
                                "MySQL returned an invalid blog hot-rank row. blogId=" + blogId +
                                        ", liked=" + liked);
                    }
                    return new Candidate(blogId, liked);
                },
                properties.getTopK());
    }

    private void stageCandidates(String temporaryKey, List<Candidate> candidates) {
        if (candidates == null || candidates.size() > properties.getTopK()) {
            throw new IllegalStateException("MySQL returned an invalid blog hot-rank candidate list");
        }

        redisTemplate.delete(temporaryKey);
        if (candidates.isEmpty()) {
            return;
        }

        Set<ZSetOperations.TypedTuple<String>> tuples = new LinkedHashSet<>(candidates.size());
        for (Candidate candidate : candidates) {
            tuples.add(new DefaultTypedTuple<>(
                    formatMember(candidate.blogId), Double.valueOf(candidate.liked)));
        }
        Long added = redisTemplate.opsForZSet().add(temporaryKey, tuples);
        if (added == null || added.longValue() != candidates.size()) {
            throw new IllegalStateException(
                    "Redis staged " + added + " of " + candidates.size() + " hot-rank candidates");
        }
        Boolean expiring = redisTemplate.expire(temporaryKey, properties.getMaxStale());
        if (!Boolean.TRUE.equals(expiring)) {
            throw new IllegalStateException(
                    "Redis could not bound the hot-rank temporary key lifetime: " + temporaryKey);
        }
    }

    private void cleanupTemporaryKey(String temporaryKey) {
        if (temporaryKey == null) {
            return;
        }
        try {
            redisTemplate.delete(temporaryKey);
        } catch (RuntimeException cleanupFailure) {
            log.warn("Unable to clean failed blog hot-rank temporary key. key={}",
                    temporaryKey, cleanupFailure);
        }
    }

    private void unlockBestEffort(RLock lock) {
        try {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        } catch (RuntimeException unlockFailure) {
            log.warn("Unable to release the blog hot-rank refresh lock", unlockFailure);
        }
    }

    static String temporaryKey(long generation) {
        return TEMP_KEY_PREFIX + generation;
    }

    static String formatMember(long blogId) {
        if (blogId <= 0L) {
            throw new IllegalArgumentException("blogId must be positive");
        }
        return String.format(Locale.ROOT, "%019d", blogId);
    }

    private static Long parseMember(String member) {
        if (member == null || member.length() != 19 || member.charAt(0) == '-') {
            return null;
        }
        for (int index = 0; index < member.length(); index++) {
            char current = member.charAt(index);
            if (current < '0' || current > '9') {
                return null;
            }
        }
        try {
            long blogId = Long.parseLong(member);
            return blogId > 0L ? blogId : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static String positiveDecimal(Object raw) {
        String value = stringValue(raw);
        if (value == null || value.isEmpty() || value.charAt(0) == '0') {
            return null;
        }
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (current < '0' || current > '9') {
                return null;
            }
        }
        try {
            return Long.parseLong(value) > 0L ? value : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static String nonNegativeDecimal(Object raw) {
        String value = stringValue(raw);
        if (value == null || value.isEmpty() ||
                (value.length() > 1 && value.charAt(0) == '0')) {
            return null;
        }
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (current < '0' || current > '9') {
                return null;
            }
        }
        try {
            return Long.parseLong(value) >= 0L ? value : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static String stringValue(Object value) {
        return value == null ? null : value.toString();
    }

    public enum RebuildOutcome {
        PUBLISHED,
        SKIPPED_LOCK_BUSY,
        STALE_GENERATION,
        FAILED
    }

    static final class Candidate {
        private final long blogId;
        private final long liked;

        Candidate(long blogId, long liked) {
            this.blogId = blogId;
            this.liked = liked;
        }
    }
}
