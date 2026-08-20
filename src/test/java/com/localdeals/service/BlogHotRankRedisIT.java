package com.localdeals.service;

import com.localdeals.config.BlogHotRankProperties;
import com.localdeals.observability.LocalDealsMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import javax.annotation.Resource;
import java.util.Arrays;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

@SpringBootTest(
        classes = {RedisAutoConfiguration.class, BlogHotRankRedisIT.Config.class},
        properties = {
                "local-deals.blog-hot-rank.read-enabled=true",
                "local-deals.blog-hot-rank.top-k=2",
                "local-deals.blog-hot-rank.page-size=2"
        })
@ActiveProfiles("test")
@org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(
        named = "LOCAL_DEALS_RUN_ISOLATED_LIKE_IT", matches = "true")
class BlogHotRankRedisIT {

    private static final DefaultRedisScript<Long> PUBLISH_SCRIPT;

    static {
        PUBLISH_SCRIPT = new DefaultRedisScript<>();
        PUBLISH_SCRIPT.setLocation(new ClassPathResource("lua/blog_hot_rank_publish.lua"));
        PUBLISH_SCRIPT.setResultType(Long.class);
    }

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Resource
    private BlogHotRankService hotRankService;

    @BeforeEach
    @AfterEach
    void cleanRankKeys() {
        Set<String> keys = redisTemplate.keys("blog:hot:{global}:*");
        if (keys != null && !keys.isEmpty()) {
            redisTemplate.delete(keys);
        }
    }

    @Test
    void staleBuilderCannotReplaceNewerCompleteGeneration() {
        redisTemplate.opsForValue().set(BlogHotRankService.GENERATION_KEY, "2");
        String stale = BlogHotRankService.temporaryKey(1L);
        String current = BlogHotRankService.temporaryKey(2L);
        redisTemplate.opsForZSet().add(stale, BlogHotRankService.formatMember(99L), 99D);
        redisTemplate.opsForZSet().add(current, BlogHotRankService.formatMember(7L), 5D);
        redisTemplate.opsForZSet().add(current, BlogHotRankService.formatMember(9L), 5D);

        assertThat(publish(stale, 1L, 1)).isZero();
        assertThat(publish(current, 2L, 2)).isEqualTo(1L);

        BlogHotRankReadResult result = hotRankService.readPage(1);
        assertThat(result.getMissReason()).isNull();
        assertThat(result.isHit()).isTrue();
        assertThat(result.getBlogIds()).containsExactly(9L, 7L);
        assertThat(redisTemplate.hasKey(stale)).isFalse();
    }

    @Test
    void committedBlogInsertionIsNxAndKeepsTheConfiguredTopKBound() {
        redisTemplate.opsForValue().set(BlogHotRankService.GENERATION_KEY, "3");
        String current = BlogHotRankService.temporaryKey(3L);
        redisTemplate.opsForZSet().add(current, BlogHotRankService.formatMember(1L), 0D);
        redisTemplate.opsForZSet().add(current, BlogHotRankService.formatMember(2L), 0D);
        assertThat(publish(current, 3L, 2)).isEqualTo(1L);

        hotRankService.addNewBlogAfterCommit(3L);

        assertThat(redisTemplate.opsForZSet().zCard(BlogHotRankService.LIVE_KEY)).isEqualTo(2L);
        BlogHotRankReadResult result = hotRankService.readPage(1);
        assertThat(result.getMissReason()).isNull();
        assertThat(result.getBlogIds()).containsExactly(3L, 2L);

        redisTemplate.opsForZSet().add(BlogHotRankService.LIVE_KEY,
                BlogHotRankService.formatMember(3L), 8D);
        hotRankService.addNewBlogAfterCommit(3L);
        assertThat(redisTemplate.opsForZSet().score(
                BlogHotRankService.LIVE_KEY, BlogHotRankService.formatMember(3L))).isEqualTo(8D);
    }

    @Test
    void committedBlogFencesABuilderWhichLoadedAnOlderDatabaseSnapshot() {
        redisTemplate.opsForValue().set(BlogHotRankService.GENERATION_KEY, "6");
        String initial = BlogHotRankService.temporaryKey(6L);
        redisTemplate.opsForZSet().add(initial, BlogHotRankService.formatMember(1L), 0D);
        redisTemplate.opsForZSet().add(initial, BlogHotRankService.formatMember(2L), 0D);
        assertThat(publish(initial, 6L, 2)).isEqualTo(1L);

        // A builder reserves generation 7 and stages a DB snapshot taken before blog 3 commits.
        assertThat(redisTemplate.opsForValue().increment(BlogHotRankService.GENERATION_KEY))
                .isEqualTo(7L);
        String staleBuilder = BlogHotRankService.temporaryKey(7L);
        redisTemplate.opsForZSet().add(staleBuilder, BlogHotRankService.formatMember(1L), 0D);
        redisTemplate.opsForZSet().add(staleBuilder, BlogHotRankService.formatMember(2L), 0D);

        hotRankService.addNewBlogAfterCommit(3L);

        assertThat(redisTemplate.opsForValue().get(BlogHotRankService.GENERATION_KEY)).isEqualTo("8");
        assertThat(redisTemplate.opsForHash().get(BlogHotRankService.META_KEY, "generation"))
                .isEqualTo("8");
        assertThat(publish(staleBuilder, 7L, 2)).isZero();
        BlogHotRankReadResult result = hotRankService.readPage(1);
        assertThat(result.getMissReason()).isNull();
        assertThat(result.getBlogIds()).containsExactly(3L, 2L);
    }

    @Test
    void anExplicitlyPublishedEmptyRankIsAHitNotAColdMiss() {
        redisTemplate.opsForValue().set(BlogHotRankService.GENERATION_KEY, "4");

        assertThat(publish(BlogHotRankService.temporaryKey(4L), 4L, 0)).isEqualTo(1L);

        BlogHotRankReadResult result = hotRankService.readPage(1);
        assertThat(result.getMissReason()).isNull();
        assertThat(result.isHit()).isTrue();
        assertThat(result.getBlogIds()).isEmpty();
    }

    @Test
    void corruptMetadataMakesTheWholeReadFallBack() {
        redisTemplate.opsForValue().set(BlogHotRankService.GENERATION_KEY, "5");
        String current = BlogHotRankService.temporaryKey(5L);
        redisTemplate.opsForZSet().add(current, BlogHotRankService.formatMember(7L), 3D);
        assertThat(publish(current, 5L, 1)).isEqualTo(1L);
        redisTemplate.opsForHash().put(
                BlogHotRankService.META_KEY, "generation", "not-a-generation");

        BlogHotRankReadResult result = hotRankService.readPage(1);

        assertThat(result.isHit()).isFalse();
        assertThat(result.getMissReason())
                .isEqualTo(BlogHotRankReadResult.MissReason.BAD_METADATA);
        assertThat(result.getBlogIds()).isEmpty();
    }

    private Long publish(String temporaryKey, long generation, int count) {
        return redisTemplate.execute(
                PUBLISH_SCRIPT,
                Arrays.asList(
                        BlogHotRankService.LIVE_KEY,
                        BlogHotRankService.META_KEY,
                        BlogHotRankService.GENERATION_KEY,
                        temporaryKey),
                Long.toString(generation), Integer.toString(count),
                Long.toString(System.currentTimeMillis()), "2");
    }

    @TestConfiguration
    static class Config {
        @Bean
        BlogHotRankProperties blogHotRankProperties() {
            return new BlogHotRankProperties();
        }

        @Bean
        JdbcTemplate jdbcTemplate() {
            return mock(JdbcTemplate.class);
        }

        @Bean
        RedissonClient redissonClient() {
            return mock(RedissonClient.class);
        }

        @Bean
        MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }

        @Bean
        LocalDealsMetrics localDealsMetrics(MeterRegistry registry) {
            return new LocalDealsMetrics(registry);
        }

        @Bean
        BlogHotRankService blogHotRankService(JdbcTemplate jdbcTemplate,
                                              StringRedisTemplate redisTemplate,
                                              RedissonClient redissonClient,
                                              BlogHotRankProperties properties,
                                              LocalDealsMetrics metrics) {
            return new BlogHotRankService(
                    jdbcTemplate, redisTemplate, redissonClient, properties, metrics);
        }
    }
}
