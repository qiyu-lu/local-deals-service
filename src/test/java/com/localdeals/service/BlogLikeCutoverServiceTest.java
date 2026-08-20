package com.localdeals.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BlogLikeCutoverServiceTest {

    private JdbcTemplate jdbcTemplate;
    private BlogLikeCutoverService service;

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        service = new BlogLikeCutoverService(jdbcTemplate);
    }

    @Test
    void recordsCompletionOnlyAfterAggregateAndOutboxInvariantsPass() {
        when(jdbcTemplate.queryForObject(
                BlogLikeCutoverService.COUNT_INVALID_AGGREGATES_SQL, Long.class)).thenReturn(0L);
        when(jdbcTemplate.queryForObject(
                BlogLikeCutoverService.COUNT_PENDING_OUTBOX_SQL, Long.class)).thenReturn(0L);
        when(jdbcTemplate.update(
                BlogLikeCutoverService.UPSERT_MARKER_SQL,
                BlogLikeCutoverService.MARKER_KEY, 2, 9L, 7L)).thenReturn(1);

        service.recordCompleted(2, 9L, 7L);

        verify(jdbcTemplate).update(
                BlogLikeCutoverService.UPSERT_MARKER_SQL,
                BlogLikeCutoverService.MARKER_KEY, 2, 9L, 7L);
    }

    @Test
    void invariantFailureCannotCreateMarker() {
        when(jdbcTemplate.queryForObject(
                BlogLikeCutoverService.COUNT_INVALID_AGGREGATES_SQL, Long.class)).thenReturn(1L);
        when(jdbcTemplate.queryForObject(
                BlogLikeCutoverService.COUNT_PENDING_OUTBOX_SQL, Long.class)).thenReturn(0L);

        assertThatThrownBy(() -> service.recordCompleted(1, 1L, 1L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("invariant");
        verify(jdbcTemplate, never()).update(
                BlogLikeCutoverService.UPSERT_MARKER_SQL,
                BlogLikeCutoverService.MARKER_KEY, 1, 1L, 1L);
    }

    @Test
    void completedMarkerIsReadExactly() {
        when(jdbcTemplate.queryForObject(
                BlogLikeCutoverService.COUNT_COMPLETED_MARKER_SQL,
                Integer.class, BlogLikeCutoverService.MARKER_KEY)).thenReturn(1);

        assertThat(service.isCompleted()).isTrue();
    }

    @Test
    void idempotentMarkerInsertAcceptsAnExistingCompletedRow() {
        when(jdbcTemplate.queryForObject(
                BlogLikeCutoverService.COUNT_INVALID_AGGREGATES_SQL, Long.class)).thenReturn(0L);
        when(jdbcTemplate.queryForObject(
                BlogLikeCutoverService.COUNT_PENDING_OUTBOX_SQL, Long.class)).thenReturn(0L);
        when(jdbcTemplate.update(
                BlogLikeCutoverService.UPSERT_MARKER_SQL,
                BlogLikeCutoverService.MARKER_KEY, 2, 9L, 0L)).thenReturn(0);
        when(jdbcTemplate.queryForObject(
                BlogLikeCutoverService.COUNT_COMPLETED_MARKER_SQL,
                Integer.class, BlogLikeCutoverService.MARKER_KEY)).thenReturn(1);

        assertThatCode(() -> service.recordCompleted(2, 9L, 0L)).doesNotThrowAnyException();
    }
}
