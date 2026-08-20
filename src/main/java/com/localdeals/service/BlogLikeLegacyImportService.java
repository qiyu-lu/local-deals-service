package com.localdeals.service;

import lombok.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Imports one bounded page of stopped-write legacy Redis identities without changing totals. */
@Service
public class BlogLikeLegacyImportService {

    static final String LOCK_BLOG_SQL =
            "SELECT liked, legacy_liked_offset FROM tb_blog WHERE id = ? FOR UPDATE";
    static final String INSERT_RELATION_SQL =
            "INSERT IGNORE INTO tb_blog_like (blog_id, user_id, liked_at) VALUES (?, ?, ?)";
    static final String REDUCE_OFFSET_SQL =
            "UPDATE tb_blog SET legacy_liked_offset = legacy_liked_offset - ? " +
                    "WHERE id = ? AND legacy_liked_offset >= ?";

    private final JdbcTemplate jdbcTemplate;

    public BlogLikeLegacyImportService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional(rollbackFor = Exception.class)
    public ImportResult importBatch(long blogId, List<LegacyLike> likes) {
        if (blogId <= 0L || likes == null || likes.isEmpty()) {
            throw new IllegalArgumentException("legacy like import requires a positive blog and non-empty batch");
        }
        List<BlogAggregate> aggregate = jdbcTemplate.query(
                LOCK_BLOG_SQL,
                (rs, rowNum) -> new BlogAggregate(
                        rs.getInt("liked"), rs.getInt("legacy_liked_offset")),
                blogId);
        if (aggregate.size() != 1) {
            throw new IllegalStateException("Legacy like key references a missing blog. blogId=" + blogId);
        }

        Integer activeBefore = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM tb_blog_like WHERE blog_id = ?", Integer.class, blogId);
        if (activeBefore == null) {
            throw new IllegalStateException("Unable to count legacy blog likes. blogId=" + blogId);
        }

        List<Object[]> arguments = new ArrayList<>(likes.size());
        Set<Long> uniqueUsers = new HashSet<>();
        for (LegacyLike like : likes) {
            if (like == null || like.userId <= 0L || like.likedAtMillis <= 0L) {
                throw new IllegalArgumentException("legacy like identity and timestamp must be positive");
            }
            if (!uniqueUsers.add(like.userId)) {
                throw new IllegalArgumentException("legacy like import batch contains a duplicate user");
            }
            arguments.add(new Object[]{blogId, like.userId, new Timestamp(like.likedAtMillis)});
        }
        // Some drivers return Statement.SUCCESS_NO_INFO for rewritten batches. The locked
        // before/after cardinality is the portable source of the exact inserted count.
        jdbcTemplate.batchUpdate(INSERT_RELATION_SQL, arguments);
        String placeholders = String.join(",", Collections.nCopies(uniqueUsers.size(), "?"));
        List<Object> ownershipArguments = new ArrayList<>(uniqueUsers.size() + 1);
        ownershipArguments.add(blogId);
        ownershipArguments.addAll(uniqueUsers);
        Integer presentFromBatch = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM tb_blog_like WHERE blog_id = ? AND user_id IN (" +
                        placeholders + ")",
                Integer.class,
                ownershipArguments.toArray());
        if (presentFromBatch == null || presentFromBatch != uniqueUsers.size()) {
            throw new IllegalStateException(
                    "Legacy like identities were not imported exactly. blogId=" + blogId);
        }
        Integer activeAfterInsert = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM tb_blog_like WHERE blog_id = ?", Integer.class, blogId);
        if (activeAfterInsert == null || activeAfterInsert < activeBefore) {
            throw new IllegalStateException("Legacy like cardinality moved backwards. blogId=" + blogId);
        }
        int inserted = activeAfterInsert - activeBefore;

        if (inserted > 0) {
            int reduced = jdbcTemplate.update(REDUCE_OFFSET_SQL, inserted, blogId, inserted);
            if (reduced != 1) {
                throw new IllegalStateException(
                        "Legacy Redis identities exceed the unattributed DB count. blogId=" + blogId +
                                ", newlyImported=" + inserted);
            }
        }

        Integer active = activeAfterInsert;
        List<BlogAggregate> after = jdbcTemplate.query(
                "SELECT liked, legacy_liked_offset FROM tb_blog WHERE id = ?",
                (rs, rowNum) -> new BlogAggregate(
                        rs.getInt("liked"), rs.getInt("legacy_liked_offset")),
                blogId);
        if (active == null || after.size() != 1 ||
                after.get(0).liked != after.get(0).legacyOffset + active) {
            throw new IllegalStateException(
                    "Legacy like invariant failed after import. blogId=" + blogId);
        }
        return new ImportResult(likes.size(), inserted, after.get(0).legacyOffset, active);
    }

    @Value
    public static class LegacyLike {
        long userId;
        long likedAtMillis;
    }

    @Value
    public static class ImportResult {
        int scanned;
        int inserted;
        int legacyOffset;
        int activeRelationships;
    }

    @Value
    static class BlogAggregate {
        int liked;
        int legacyOffset;
    }
}
