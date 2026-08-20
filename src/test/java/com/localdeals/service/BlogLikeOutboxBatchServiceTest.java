package com.localdeals.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BlogLikeOutboxBatchServiceTest {

    private JdbcTemplate jdbcTemplate;
    private BlogLikeOutboxBatchService service;

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        service = new BlogLikeOutboxBatchService(jdbcTemplate);
    }

    @Test
    @SuppressWarnings("unchecked")
    void emptyQueueDoesNotWriteAnything() {
        when(jdbcTemplate.query(
                eq(BlogLikeOutboxBatchService.SELECT_PENDING_SQL),
                any(RowMapper.class), eq(100)))
                .thenReturn(Collections.emptyList());

        BlogLikeOutboxBatchService.BatchResult result = service.processNextBatch(100);

        assertThat(result.getProcessedEvents()).isZero();
        verify(jdbcTemplate, never()).batchUpdate(anyString(), any(List.class));
        verify(jdbcTemplate, never()).update(anyString(), any(Object[].class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void groupsDeltasByBlogAndMarksOnlyTheExactSelectedIds() {
        List<BlogLikeOutboxBatchService.OutboxRow> rows = Arrays.asList(
                new BlogLikeOutboxBatchService.OutboxRow(1L, 20L, 1),
                new BlogLikeOutboxBatchService.OutboxRow(2L, 10L, 1),
                new BlogLikeOutboxBatchService.OutboxRow(3L, 20L, -1),
                new BlogLikeOutboxBatchService.OutboxRow(4L, 10L, 1));
        when(jdbcTemplate.query(
                eq(BlogLikeOutboxBatchService.SELECT_PENDING_SQL),
                any(RowMapper.class), eq(100))).thenReturn(rows);
        when(jdbcTemplate.update(
                BlogLikeOutboxBatchService.UPDATE_AGGREGATE_SQL, 2, 10L, 2))
                .thenReturn(1);
        when(jdbcTemplate.update(
                eq(BlogLikeOutboxBatchService.MARK_PROCESSED_SQL_PREFIX + "?,?,?,?)"),
                eq(1L), eq(2L), eq(3L), eq(4L))).thenReturn(4);

        BlogLikeOutboxBatchService.BatchResult result = service.processNextBatch(100);

        assertThat(result.getProcessedEvents()).isEqualTo(4);
        assertThat(result.getUpdatedBlogs()).isEqualTo(1);
        assertThat(result.getEventIds()).containsExactly(1L, 2L, 3L, 4L);
        verify(jdbcTemplate).update(
                BlogLikeOutboxBatchService.UPDATE_AGGREGATE_SQL, 2, 10L, 2);
        verify(jdbcTemplate).update(
                BlogLikeOutboxBatchService.MARK_PROCESSED_SQL_PREFIX + "?,?,?,?)",
                1L, 2L, 3L, 4L);
    }

    @Test
    @SuppressWarnings("unchecked")
    void malformedDeltaStopsBeforeAggregateOrMarker() {
        when(jdbcTemplate.query(
                eq(BlogLikeOutboxBatchService.SELECT_PENDING_SQL),
                any(RowMapper.class), eq(10)))
                .thenReturn(Collections.singletonList(
                        new BlogLikeOutboxBatchService.OutboxRow(9L, 20L, 2)));

        assertThatThrownBy(() -> service.processNextBatch(10))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("delta");
        verify(jdbcTemplate, never()).batchUpdate(anyString(), any(List.class));
        verify(jdbcTemplate, never()).update(anyString(), any(Object[].class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void unsafeAggregateResultStopsBeforeEventsAreMarked() {
        when(jdbcTemplate.query(
                eq(BlogLikeOutboxBatchService.SELECT_PENDING_SQL),
                any(RowMapper.class), eq(10)))
                .thenReturn(Collections.singletonList(
                        new BlogLikeOutboxBatchService.OutboxRow(9L, 20L, -1)));
        when(jdbcTemplate.update(
                BlogLikeOutboxBatchService.UPDATE_AGGREGATE_SQL, -1, 20L, -1))
                .thenReturn(0);

        assertThatThrownBy(() -> service.processNextBatch(10))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("safely");
        verify(jdbcTemplate, never()).update(
                eq(BlogLikeOutboxBatchService.MARK_PROCESSED_SQL_PREFIX + "?)"),
                any(Object[].class));
    }
}
