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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BlogLikeLegacyImportServiceTest {

    private JdbcTemplate jdbcTemplate;
    private BlogLikeLegacyImportService service;

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        service = new BlogLikeLegacyImportService(jdbcTemplate);
    }

    @Test
    @SuppressWarnings("unchecked")
    void insertsOnlyNewIdentitiesAndReducesOffsetByTheSameAmount() {
        when(jdbcTemplate.query(
                eq(BlogLikeLegacyImportService.LOCK_BLOG_SQL),
                any(RowMapper.class), eq(41L)))
                .thenReturn(Collections.singletonList(
                        new BlogLikeLegacyImportService.BlogAggregate(7, 5)));
        when(jdbcTemplate.batchUpdate(
                eq(BlogLikeLegacyImportService.INSERT_RELATION_SQL), any(List.class)))
                .thenReturn(new int[]{java.sql.Statement.SUCCESS_NO_INFO});
        when(jdbcTemplate.update(
                BlogLikeLegacyImportService.REDUCE_OFFSET_SQL, 2, 41L, 2)).thenReturn(1);
        when(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM tb_blog_like WHERE blog_id = ?", Integer.class, 41L))
                .thenReturn(2, 4);
        when(jdbcTemplate.queryForObject(
                org.mockito.ArgumentMatchers.startsWith(
                        "SELECT COUNT(*) FROM tb_blog_like WHERE blog_id = ? AND user_id IN ("),
                eq(Integer.class), eq(41L),
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyLong()))
                .thenReturn(3);
        when(jdbcTemplate.query(
                eq("SELECT liked, legacy_liked_offset FROM tb_blog WHERE id = ?"),
                any(RowMapper.class), eq(41L)))
                .thenReturn(Collections.singletonList(
                        new BlogLikeLegacyImportService.BlogAggregate(7, 3)));

        BlogLikeLegacyImportService.ImportResult result = service.importBatch(
                41L, Arrays.asList(
                        new BlogLikeLegacyImportService.LegacyLike(1L, 1_000L),
                        new BlogLikeLegacyImportService.LegacyLike(2L, 2_000L),
                        new BlogLikeLegacyImportService.LegacyLike(3L, 3_000L)));

        assertThat(result.getScanned()).isEqualTo(3);
        assertThat(result.getInserted()).isEqualTo(2);
        assertThat(result.getLegacyOffset()).isEqualTo(3);
        assertThat(result.getActiveRelationships()).isEqualTo(4);
    }

    @Test
    @SuppressWarnings("unchecked")
    void excessRedisIdentitiesFailInsteadOfInventingANegativeOffset() {
        when(jdbcTemplate.query(
                eq(BlogLikeLegacyImportService.LOCK_BLOG_SQL),
                any(RowMapper.class), eq(41L)))
                .thenReturn(Collections.singletonList(
                        new BlogLikeLegacyImportService.BlogAggregate(1, 1)));
        when(jdbcTemplate.batchUpdate(
                eq(BlogLikeLegacyImportService.INSERT_RELATION_SQL), any(List.class)))
                .thenReturn(new int[]{java.sql.Statement.SUCCESS_NO_INFO});
        when(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM tb_blog_like WHERE blog_id = ?", Integer.class, 41L))
                .thenReturn(0, 2);
        when(jdbcTemplate.queryForObject(
                org.mockito.ArgumentMatchers.startsWith(
                        "SELECT COUNT(*) FROM tb_blog_like WHERE blog_id = ? AND user_id IN ("),
                eq(Integer.class), eq(41L),
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyLong()))
                .thenReturn(2);
        when(jdbcTemplate.update(
                BlogLikeLegacyImportService.REDUCE_OFFSET_SQL, 2, 41L, 2)).thenReturn(0);

        assertThatThrownBy(() -> service.importBatch(
                41L, Arrays.asList(
                        new BlogLikeLegacyImportService.LegacyLike(1L, 1_000L),
                        new BlogLikeLegacyImportService.LegacyLike(2L, 2_000L))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("exceed");
        verify(jdbcTemplate, never()).query(
                eq("SELECT liked, legacy_liked_offset FROM tb_blog WHERE id = ?"),
                any(RowMapper.class), eq(41L));
    }
}
