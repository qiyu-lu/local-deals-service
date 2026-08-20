package com.localdeals.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Persists and verifies the stopped-write V8 blog-like migration boundary. */
@Service
public class BlogLikeCutoverService {

    static final String MARKER_KEY = "V8_LEGACY_REDIS_IMPORT";
    static final String COUNT_INVALID_AGGREGATES_SQL =
            "SELECT COUNT(*) FROM tb_blog b " +
                    "LEFT JOIN (SELECT blog_id, COUNT(*) AS active_count " +
                    "FROM tb_blog_like GROUP BY blog_id) l ON l.blog_id = b.id " +
                    "WHERE b.liked <> b.legacy_liked_offset + COALESCE(l.active_count, 0)";
    static final String COUNT_PENDING_OUTBOX_SQL =
            "SELECT COUNT(*) FROM tb_blog_like_outbox WHERE processed_time IS NULL";
    static final String UPSERT_MARKER_SQL =
            "INSERT INTO tb_blog_like_cutover " +
                    "(marker_key, status, source_key_count, source_member_count, imported_count, completed_at) " +
                    "VALUES (?, 'COMPLETED', ?, ?, ?, CURRENT_TIMESTAMP(3)) " +
                    "ON DUPLICATE KEY UPDATE marker_key = VALUES(marker_key)";
    static final String COUNT_COMPLETED_MARKER_SQL =
            "SELECT COUNT(*) FROM tb_blog_like_cutover " +
                    "WHERE marker_key = ? AND status = 'COMPLETED'";

    private final JdbcTemplate jdbcTemplate;

    public BlogLikeCutoverService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional
    public void recordCompleted(int sourceKeys, long sourceMembers, long imported) {
        if (sourceKeys < 0 || sourceMembers < 0L || imported < 0L || imported > sourceMembers) {
            throw new IllegalArgumentException("Invalid blog-like cutover counters");
        }
        Long invalidAggregates = jdbcTemplate.queryForObject(
                COUNT_INVALID_AGGREGATES_SQL, Long.class);
        Long pendingOutbox = jdbcTemplate.queryForObject(COUNT_PENDING_OUTBOX_SQL, Long.class);
        if (invalidAggregates == null || invalidAggregates != 0L) {
            throw new IllegalStateException(
                    "Blog-like cutover aggregate invariant failed. invalidBlogs=" + invalidAggregates);
        }
        if (pendingOutbox == null || pendingOutbox != 0L) {
            throw new IllegalStateException(
                    "Blog-like cutover requires an empty pending outbox. pending=" + pendingOutbox);
        }
        int affected = jdbcTemplate.update(
                UPSERT_MARKER_SQL, MARKER_KEY, sourceKeys, sourceMembers, imported);
        if (affected < 0 || (affected == 0 && !isCompleted())) {
            throw new IllegalStateException("Unable to persist the blog-like cutover marker");
        }
    }

    @Transactional(readOnly = true)
    public boolean isCompleted() {
        Integer count = jdbcTemplate.queryForObject(
                COUNT_COMPLETED_MARKER_SQL, Integer.class, MARKER_KEY);
        return count != null && count == 1;
    }
}
