package com.localdeals.init;

import com.localdeals.config.BlogLikeProperties;
import com.localdeals.service.BlogLikeLegacyImportService;
import com.localdeals.service.BlogLikeCutoverService;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RKeys;
import org.redisson.api.RedissonClient;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static com.localdeals.utils.RedisConstants.BLOG_LIKED_KEY;

/** One-startup, stopped-write importer for the tutorial's Redis-only like identities. */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
@ConditionalOnProperty(
        prefix = "local-deals.blog-like",
        name = "legacy-backfill-on-startup",
        havingValue = "true")
public class BlogLikeLegacyBackfillRunner implements ApplicationRunner {

    private static final long MAX_FUTURE_SKEW_MILLIS = 5L * 60L * 1_000L;

    private final RedissonClient redissonClient;
    private final StringRedisTemplate redisTemplate;
    private final BlogLikeLegacyImportService importService;
    private final BlogLikeProperties properties;
    private final BlogLikeCutoverService cutoverService;

    public BlogLikeLegacyBackfillRunner(RedissonClient redissonClient,
                                        StringRedisTemplate redisTemplate,
                                        BlogLikeLegacyImportService importService,
                                        BlogLikeProperties properties,
                                        BlogLikeCutoverService cutoverService) {
        this.redissonClient = redissonClient;
        this.redisTemplate = redisTemplate;
        this.importService = importService;
        this.properties = properties;
        this.cutoverService = cutoverService;
    }

    @Override
    public void run(ApplicationArguments args) {
        BackfillSummary summary = backfillOnce();
        cutoverService.recordCompleted(summary.keys, summary.scanned, summary.inserted);
        log.info("Legacy blog-like identity backfill completed. keys={}, scanned={}, inserted={}",
                summary.keys, summary.scanned, summary.inserted);
    }

    BackfillSummary backfillOnce() {
        BackfillSummary summary = new BackfillSummary();
        long latestAcceptedTimestamp = System.currentTimeMillis() + MAX_FUTURE_SKEW_MILLIS;
        RKeys keys = redissonClient.getKeys();
        for (String key : keys.getKeysByPattern(
                BLOG_LIKED_KEY + "*", properties.getLegacyScanCount())) {
            long blogId = parseBlogId(key);
            summary.keys++;
            Long expectedCardinality = redisTemplate.opsForZSet().zCard(key);
            if (expectedCardinality == null) {
                throw new IllegalStateException(
                        "Redis returned null cardinality for legacy likes. key=" + key);
            }
            long offset = 0L;
            while (true) {
                long end = offset + properties.getLegacyBatchSize() - 1L;
                Set<ZSetOperations.TypedTuple<String>> tuples =
                        redisTemplate.opsForZSet().rangeWithScores(key, offset, end);
                if (tuples == null) {
                    throw new IllegalStateException("Redis returned null while reading legacy likes. key=" + key);
                }
                if (tuples.isEmpty()) {
                    break;
                }
                List<BlogLikeLegacyImportService.LegacyLike> likes = new ArrayList<>(tuples.size());
                for (ZSetOperations.TypedTuple<String> tuple : tuples) {
                    Long userId = parseCanonicalPositiveLong(tuple.getValue());
                    Double score = tuple.getScore();
                    if (userId == null || score == null || !Double.isFinite(score) ||
                            score <= 0D || score > latestAcceptedTimestamp ||
                            score.doubleValue() != Math.rint(score.doubleValue())) {
                        throw new IllegalStateException(
                                "Legacy like contains an invalid user or timestamp. key=" + key);
                    }
                    likes.add(new BlogLikeLegacyImportService.LegacyLike(
                            userId, score.longValue()));
                }
                BlogLikeLegacyImportService.ImportResult result =
                        importService.importBatch(blogId, likes);
                summary.scanned += result.getScanned();
                summary.inserted += result.getInserted();
                offset += tuples.size();
            }
            Long finalCardinality = redisTemplate.opsForZSet().zCard(key);
            if (!expectedCardinality.equals(finalCardinality) || offset != expectedCardinality) {
                throw new IllegalStateException(
                        "Legacy Redis like key changed during stopped-write import. key=" + key +
                                ", before=" + expectedCardinality + ", scanned=" + offset +
                                ", after=" + finalCardinality);
            }
        }
        return summary;
    }

    private static long parseBlogId(String key) {
        if (key == null || !key.startsWith(BLOG_LIKED_KEY)) {
            throw new IllegalStateException("Invalid legacy blog-like key: " + key);
        }
        Long parsed = parseCanonicalPositiveLong(key.substring(BLOG_LIKED_KEY.length()));
        if (parsed == null) {
            throw new IllegalStateException("Invalid legacy blog-like key: " + key);
        }
        return parsed;
    }

    private static Long parseCanonicalPositiveLong(String raw) {
        if (raw == null || raw.isEmpty() || raw.charAt(0) == '0') {
            return null;
        }
        for (int index = 0; index < raw.length(); index++) {
            char current = raw.charAt(index);
            if (current < '0' || current > '9') {
                return null;
            }
        }
        try {
            long value = Long.parseLong(raw);
            return value > 0L ? value : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    static class BackfillSummary {
        private int keys;
        private int scanned;
        private int inserted;

        int getKeys() { return keys; }
        int getScanned() { return scanned; }
        int getInserted() { return inserted; }
    }
}
