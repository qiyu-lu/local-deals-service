package com.localdeals.service;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.util.StreamUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/** Pure resource-contract test; no Spring context or Redis server is used. */
class BlogHotRankLuaContractTest {

    @Test
    void publicationIsGenerationFencedAndSupportsAnEmptyReadyRank() throws IOException {
        String script = readScript("lua/blog_hot_rank_publish.lua");

        int generationRead = script.indexOf("redis.call('GET', KEYS[3])");
        int staleGuard = script.indexOf("currentGeneration ~= ARGV[1]");
        int staleCleanup = script.indexOf("redis.call('DEL', KEYS[4])");
        int emptyGuard = script.indexOf("expectedCount == 0");
        int emptyLiveDelete = script.indexOf("redis.call('DEL', KEYS[1])");
        int candidateSizeCheck = script.indexOf("redis.call('ZCARD', KEYS[4])");
        int rename = script.indexOf("redis.call('RENAME', KEYS[4], KEYS[1])");
        int readyMetadata = script.indexOf("redis.call('HSET', KEYS[2]");

        assertThat(generationRead).isGreaterThanOrEqualTo(0);
        assertThat(staleGuard).isGreaterThan(generationRead);
        assertThat(staleCleanup).isGreaterThan(staleGuard);
        assertThat(emptyGuard).isGreaterThan(staleCleanup);
        assertThat(emptyLiveDelete).isGreaterThan(emptyGuard);
        assertThat(candidateSizeCheck).isGreaterThan(emptyLiveDelete);
        assertThat(rename).isGreaterThan(candidateSizeCheck);
        assertThat(readyMetadata).isGreaterThan(rename);
        assertThat(script).contains(
                "'ready', '1'",
                "'generation', ARGV[1]",
                "'count', ARGV[2]",
                "'capacity', ARGV[4]",
                "'publishedAt', ARGV[3]",
                "return 0",
                "return 1");
    }

    @Test
    void newBlogInsertionIsReadyGatedNxAndAtomicallyTrimmedToTopK() throws IOException {
        String script = readScript("lua/blog_hot_rank_add_new.lua");

        int readyGuard = script.indexOf("redis.call('HGET', KEYS[2], 'ready') ~= '1'");
        int nxInsert = script.indexOf("redis.call('ZADD', KEYS[1], 'NX', 0, ARGV[1])");
        int sizeRead = script.indexOf("redis.call('ZCARD', KEYS[1])");
        int boundedGuard = script.indexOf("size > topK");
        int trim = script.indexOf("redis.call('ZREMRANGEBYRANK', KEYS[1], 0, size - topK - 1)");
        int generationFence = script.indexOf("local nextGeneration = redis.call('INCR', KEYS[3])");
        int metadataFence = script.indexOf("'generation', tostring(nextGeneration)");

        assertThat(readyGuard).isGreaterThanOrEqualTo(0);
        assertThat(nxInsert).isGreaterThan(readyGuard);
        assertThat(sizeRead).isGreaterThan(nxInsert);
        assertThat(boundedGuard).isGreaterThan(sizeRead);
        assertThat(trim).isGreaterThan(boundedGuard);
        assertThat(generationFence).isGreaterThan(trim);
        assertThat(metadataFence).isGreaterThan(generationFence);
        assertThat(script).contains("lessThan(generation, metaGeneration)");
        assertThat(script).doesNotContain("'XX'");
    }

    private static String readScript(String path) throws IOException {
        return StreamUtils.copyToString(
                new ClassPathResource(path).getInputStream(), StandardCharsets.UTF_8);
    }
}
