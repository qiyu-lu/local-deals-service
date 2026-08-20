package com.localdeals.service;

import com.localdeals.config.BlogLikeProperties;
import com.localdeals.dto.BlogLikeCommandResult;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Durable command and read model for blog likes.
 *
 * <p>MySQL is authoritative. Redis projections may be rebuilt from these rows and are never
 * consulted to decide whether a state transition is a duplicate.</p>
 */
@Service
public class BlogLikeCommandService {

    static final String LOCK_BLOG_SQL =
            "SELECT id FROM tb_blog WHERE id = ? LOCK IN SHARE MODE";
    static final String INSERT_LIKE_SQL =
            "INSERT INTO tb_blog_like (blog_id, user_id, liked_at) " +
                    "VALUES (?, ?, CURRENT_TIMESTAMP(3))";
    static final String DELETE_LIKE_SQL =
            "DELETE FROM tb_blog_like WHERE blog_id = ? AND user_id = ?";
    static final String INSERT_OUTBOX_SQL =
            "INSERT INTO tb_blog_like_outbox (blog_id, delta, create_time) " +
                    "VALUES (?, ?, CURRENT_TIMESTAMP(3))";
    static final String IS_LIKED_SQL =
            "SELECT COUNT(*) FROM tb_blog_like WHERE blog_id = ? AND user_id = ?";
    static final String TOP_FIVE_USER_IDS_SQL =
            "SELECT user_id FROM tb_blog_like WHERE blog_id = ? " +
                    "ORDER BY liked_at ASC, user_id ASC LIMIT 5";
    static final String FIND_LIKED_BLOG_IDS_SQL_PREFIX =
            "SELECT blog_id FROM tb_blog_like WHERE user_id = ? AND blog_id IN (";

    private final JdbcTemplate jdbcTemplate;
    private final BlogLikeProperties properties;

    public BlogLikeCommandService(JdbcTemplate jdbcTemplate, BlogLikeProperties properties) {
        this.jdbcTemplate = jdbcTemplate;
        this.properties = properties;
    }

    /**
     * Sets an explicit desired state. Repeated PUT-like or DELETE-like commands are no-ops.
     *
     * <p>The shared parent-row lock lets concurrent likes proceed while preventing a concurrent
     * blog delete from passing the existence check before the relationship and its outbox event
     * commit. The V8 foreign keys remain the final database integrity boundary.</p>
     */
    @Transactional
    public BlogLikeCommandResult setLiked(Long blogId, Long userId, boolean desired) {
        if (!properties.isWriteEnabled()) {
            throw new IllegalStateException("Blog-like writes are disabled for maintenance");
        }
        requirePositive(blogId, "blogId");
        requirePositive(userId, "userId");

        if (!blogExistsAndIsShareLocked(blogId)) {
            return BlogLikeCommandResult.notFound(desired);
        }

        int changedRows;
        if (desired) {
            try {
                changedRows = jdbcTemplate.update(INSERT_LIKE_SQL, blogId, userId);
            } catch (DuplicateKeyException duplicateDesiredState) {
                return BlogLikeCommandResult.unchanged(true);
            }
        } else {
            changedRows = jdbcTemplate.update(DELETE_LIKE_SQL, blogId, userId);
        }
        if (changedRows == 0) {
            return BlogLikeCommandResult.unchanged(desired);
        }
        if (changedRows != 1) {
            throw new IllegalStateException(
                    "Unexpected blog-like mutation row count: " + changedRows);
        }

        int outboxRows = jdbcTemplate.update(
                INSERT_OUTBOX_SQL, blogId, desired ? 1 : -1);
        if (outboxRows != 1) {
            throw new IllegalStateException(
                    "Blog-like mutation was not paired with exactly one outbox row");
        }
        return BlogLikeCommandResult.changed(desired);
    }

    @Transactional(readOnly = true)
    public boolean isLiked(Long blogId, Long userId) {
        requirePositive(blogId, "blogId");
        requirePositive(userId, "userId");
        Integer count = jdbcTemplate.queryForObject(IS_LIKED_SQL, Integer.class, blogId, userId);
        return count != null && count > 0;
    }

    /** Returns the first five likers, preserving the legacy oldest-like-first contract. */
    @Transactional(readOnly = true)
    public List<Long> findTopFiveUserIds(Long blogId) {
        requirePositive(blogId, "blogId");
        return jdbcTemplate.queryForList(TOP_FIVE_USER_IDS_SQL, Long.class, blogId);
    }

    /** Batch lookup used to hydrate one bounded list page without an N+1 query pattern. */
    @Transactional(readOnly = true)
    public Set<Long> findLikedBlogIds(Long userId, List<Long> blogIds) {
        requirePositive(userId, "userId");
        if (blogIds == null || blogIds.isEmpty()) {
            return Collections.emptySet();
        }
        List<Object> arguments = new ArrayList<>(blogIds.size() + 1);
        arguments.add(userId);
        for (Long blogId : blogIds) {
            requirePositive(blogId, "blogId");
            arguments.add(blogId);
        }
        String placeholders = String.join(",", Collections.nCopies(blogIds.size(), "?"));
        List<Long> likedIds = jdbcTemplate.queryForList(
                FIND_LIKED_BLOG_IDS_SQL_PREFIX + placeholders + ")",
                Long.class,
                arguments.toArray());
        return likedIds == null ? Collections.emptySet() : new LinkedHashSet<>(likedIds);
    }

    private boolean blogExistsAndIsShareLocked(Long blogId) {
        List<Long> ids = jdbcTemplate.queryForList(LOCK_BLOG_SQL, Long.class, blogId);
        return ids != null && !ids.isEmpty();
    }

    private static void requirePositive(Long value, String field) {
        if (value == null || value <= 0L) {
            throw new IllegalArgumentException(field + " must be positive");
        }
    }
}
