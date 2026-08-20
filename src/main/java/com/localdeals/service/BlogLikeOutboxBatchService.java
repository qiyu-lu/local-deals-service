package com.localdeals.service;

import lombok.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Applies a bounded prefix of the transactional like outbox.
 *
 * <p>The aggregate update and the processed marker commit together. A crash before commit
 * replays the whole batch, while a crash after commit cannot replay a processed delta.</p>
 */
@Service
public class BlogLikeOutboxBatchService {

    static final String SELECT_PENDING_SQL =
            "SELECT id, blog_id, delta FROM tb_blog_like_outbox " +
                    "WHERE processed_time IS NULL ORDER BY id LIMIT ? FOR UPDATE";
    static final String UPDATE_AGGREGATE_SQL =
            "UPDATE tb_blog SET liked = CAST(liked AS SIGNED) + ? " +
                    "WHERE id = ? AND CAST(liked AS SIGNED) + ? >= 0";
    static final String MARK_PROCESSED_SQL_PREFIX =
            "UPDATE tb_blog_like_outbox SET processed_time = CURRENT_TIMESTAMP(3) " +
                    "WHERE processed_time IS NULL AND id IN (";

    private final JdbcTemplate jdbcTemplate;

    public BlogLikeOutboxBatchService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public BatchResult processNextBatch(int batchSize) {
        if (batchSize <= 0) {
            throw new IllegalArgumentException("blog-like outbox batchSize must be positive");
        }
        List<OutboxRow> rows = jdbcTemplate.query(
                SELECT_PENDING_SQL,
                (rs, rowNum) -> new OutboxRow(
                        rs.getLong("id"), rs.getLong("blog_id"), rs.getInt("delta")),
                batchSize);
        if (rows.isEmpty()) {
            return BatchResult.empty();
        }

        Map<Long, Integer> deltasByBlog = new TreeMap<>();
        List<Long> eventIds = new ArrayList<>(rows.size());
        for (OutboxRow row : rows) {
            if (row.delta != 1 && row.delta != -1) {
                throw new IllegalStateException(
                        "Invalid blog-like outbox delta. eventId=" + row.id + ", delta=" + row.delta);
            }
            eventIds.add(row.id);
            deltasByBlog.merge(row.blogId, row.delta, Integer::sum);
        }

        int updatedBlogs = 0;
        for (Map.Entry<Long, Integer> entry : deltasByBlog.entrySet()) {
            int delta = entry.getValue();
            if (delta == 0) {
                continue;
            }
            // Execute one statement per aggregate. JDBC batch updates may legally report
            // SUCCESS_NO_INFO (-2), which is insufficient for this safety check.
            int updated = jdbcTemplate.update(
                    UPDATE_AGGREGATE_SQL, delta, entry.getKey(), delta);
            if (updated != 1) {
                throw new IllegalStateException(
                        "Unable to apply blog-like aggregate safely. blogId=" + entry.getKey() +
                                ", delta=" + delta);
            }
            updatedBlogs++;
        }

        String placeholders = String.join(",", Collections.nCopies(eventIds.size(), "?"));
        int marked = jdbcTemplate.update(
                MARK_PROCESSED_SQL_PREFIX + placeholders + ")",
                eventIds.toArray());
        if (marked != rows.size()) {
            throw new IllegalStateException(
                    "Blog-like outbox marker count mismatch. expected=" + rows.size() + ", actual=" + marked);
        }
        return new BatchResult(rows.size(), updatedBlogs, eventIds);
    }

    @Value
    static class OutboxRow {
        long id;
        long blogId;
        int delta;
    }

    @Value
    public static class BatchResult {
        int processedEvents;
        int updatedBlogs;
        List<Long> eventIds;

        private static BatchResult empty() {
            return new BatchResult(0, 0, Collections.emptyList());
        }
    }
}
