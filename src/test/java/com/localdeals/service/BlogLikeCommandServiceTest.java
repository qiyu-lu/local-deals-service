package com.localdeals.service;

import com.localdeals.config.BlogLikeProperties;
import com.localdeals.dto.BlogLikeCommandResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Arrays;
import java.util.Collections;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class BlogLikeCommandServiceTest {

    private static final Long BLOG_ID = 41L;
    private static final Long USER_ID = 73L;

    private JdbcTemplate jdbcTemplate;
    private BlogLikeCommandService service;

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        BlogLikeProperties properties = new BlogLikeProperties();
        properties.setWriteEnabled(true);
        service = new BlogLikeCommandService(jdbcTemplate, properties);
    }

    @Test
    void setLiked_likeCreatesRelationshipAndPositiveOutboxDelta() {
        blogExists();
        when(jdbcTemplate.update(BlogLikeCommandService.INSERT_LIKE_SQL, BLOG_ID, USER_ID))
                .thenReturn(1);
        when(jdbcTemplate.update(BlogLikeCommandService.INSERT_OUTBOX_SQL, BLOG_ID, 1))
                .thenReturn(1);

        BlogLikeCommandResult result = service.setLiked(BLOG_ID, USER_ID, true);

        assertThat(result.getOutcome()).isEqualTo(BlogLikeCommandResult.Outcome.CHANGED);
        assertThat(result.isDesired()).isTrue();
        assertThat(result.isChanged()).isTrue();
        verify(jdbcTemplate).update(BlogLikeCommandService.INSERT_OUTBOX_SQL, BLOG_ID, 1);
    }

    @Test
    void setLiked_repeatedLikeIsNoopAndDoesNotWriteOutbox() {
        blogExists();
        when(jdbcTemplate.update(BlogLikeCommandService.INSERT_LIKE_SQL, BLOG_ID, USER_ID))
                .thenThrow(new DuplicateKeyException("already liked"));

        BlogLikeCommandResult result = service.setLiked(BLOG_ID, USER_ID, true);

        assertThat(result.getOutcome()).isEqualTo(BlogLikeCommandResult.Outcome.UNCHANGED);
        assertThat(result.isDesired()).isTrue();
        assertThat(result.isChanged()).isFalse();
        verify(jdbcTemplate, never()).update(
                eq(BlogLikeCommandService.INSERT_OUTBOX_SQL), anyLong(), anyInt());
    }

    @Test
    void setLiked_unlikeCreatesNegativeOutboxDelta() {
        blogExists();
        when(jdbcTemplate.update(BlogLikeCommandService.DELETE_LIKE_SQL, BLOG_ID, USER_ID))
                .thenReturn(1);
        when(jdbcTemplate.update(BlogLikeCommandService.INSERT_OUTBOX_SQL, BLOG_ID, -1))
                .thenReturn(1);

        BlogLikeCommandResult result = service.setLiked(BLOG_ID, USER_ID, false);

        assertThat(result.getOutcome()).isEqualTo(BlogLikeCommandResult.Outcome.CHANGED);
        assertThat(result.isDesired()).isFalse();
        assertThat(result.isChanged()).isTrue();
        verify(jdbcTemplate).update(BlogLikeCommandService.INSERT_OUTBOX_SQL, BLOG_ID, -1);
    }

    @Test
    void setLiked_repeatedUnlikeIsNoopAndDoesNotWriteOutbox() {
        blogExists();
        when(jdbcTemplate.update(BlogLikeCommandService.DELETE_LIKE_SQL, BLOG_ID, USER_ID))
                .thenReturn(0);

        BlogLikeCommandResult result = service.setLiked(BLOG_ID, USER_ID, false);

        assertThat(result.getOutcome()).isEqualTo(BlogLikeCommandResult.Outcome.UNCHANGED);
        assertThat(result.isDesired()).isFalse();
        assertThat(result.isChanged()).isFalse();
        verify(jdbcTemplate, never()).update(
                eq(BlogLikeCommandService.INSERT_OUTBOX_SQL), anyLong(), anyInt());
    }

    @Test
    void setLiked_missingBlogIsExplicitNotFoundAndDoesNotMutate() {
        when(jdbcTemplate.queryForList(
                BlogLikeCommandService.LOCK_BLOG_SQL, Long.class, BLOG_ID))
                .thenReturn(Collections.emptyList());

        BlogLikeCommandResult result = service.setLiked(BLOG_ID, USER_ID, true);

        assertThat(result.getOutcome()).isEqualTo(BlogLikeCommandResult.Outcome.NOT_FOUND);
        assertThat(result.isDesired()).isTrue();
        assertThat(result.isChanged()).isFalse();
        verify(jdbcTemplate, never()).update(
                eq(BlogLikeCommandService.INSERT_LIKE_SQL), anyLong(), anyLong());
        verify(jdbcTemplate, never()).update(
                eq(BlogLikeCommandService.INSERT_OUTBOX_SQL), anyLong(), anyInt());
    }

    @Test
    void setLiked_outboxFailurePropagatesSoTransactionCanRollBack() {
        blogExists();
        when(jdbcTemplate.update(BlogLikeCommandService.INSERT_LIKE_SQL, BLOG_ID, USER_ID))
                .thenReturn(1);
        DataAccessResourceFailureException failure =
                new DataAccessResourceFailureException("outbox unavailable");
        when(jdbcTemplate.update(BlogLikeCommandService.INSERT_OUTBOX_SQL, BLOG_ID, 1))
                .thenThrow(failure);

        assertThatThrownBy(() -> service.setLiked(BLOG_ID, USER_ID, true))
                .isSameAs(failure);
    }

    @Test
    void readsUseDurableRelationshipRows() {
        when(jdbcTemplate.queryForObject(
                BlogLikeCommandService.IS_LIKED_SQL, Integer.class, BLOG_ID, USER_ID))
                .thenReturn(1);
        when(jdbcTemplate.queryForList(
                BlogLikeCommandService.TOP_FIVE_USER_IDS_SQL, Long.class, BLOG_ID))
                .thenReturn(Arrays.asList(7L, 11L, 19L));

        assertThat(service.isLiked(BLOG_ID, USER_ID)).isTrue();
        assertThat(service.findTopFiveUserIds(BLOG_ID)).containsExactly(7L, 11L, 19L);
    }

    @Test
    void boundedListHydrationUsesOneBatchRelationshipQuery() {
        when(jdbcTemplate.queryForList(
                BlogLikeCommandService.FIND_LIKED_BLOG_IDS_SQL_PREFIX + "?,?,?)",
                Long.class, USER_ID, 41L, 42L, 43L))
                .thenReturn(Arrays.asList(41L, 43L));

        Set<Long> liked = service.findLikedBlogIds(USER_ID, Arrays.asList(41L, 42L, 43L));

        assertThat(liked).containsExactly(41L, 43L);
    }

    @Test
    void invalidIdentifiersFailBeforeJdbcAccess() {
        assertThatThrownBy(() -> service.setLiked(null, USER_ID, true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("blogId");
        assertThatThrownBy(() -> service.isLiked(BLOG_ID, 0L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("userId");
        verifyNoInteractions(jdbcTemplate);
    }

    @Test
    void maintenanceGateFailsBeforeJdbcMutation() {
        BlogLikeProperties properties = new BlogLikeProperties();
        properties.setWriteEnabled(false);
        BlogLikeCommandService gated = new BlogLikeCommandService(jdbcTemplate, properties);

        assertThatThrownBy(() -> gated.setLiked(BLOG_ID, USER_ID, true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("disabled");
        verifyNoInteractions(jdbcTemplate);
    }

    private void blogExists() {
        when(jdbcTemplate.queryForList(
                BlogLikeCommandService.LOCK_BLOG_SQL, Long.class, BLOG_ID))
                .thenReturn(Collections.singletonList(BLOG_ID));
    }
}
