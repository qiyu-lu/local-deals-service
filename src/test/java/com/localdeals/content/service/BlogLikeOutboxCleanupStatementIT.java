package com.localdeals.content.service;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * The outbox cleanup runs on a schedule and on a table nothing sharded, and its only other test
 * hands it a mocked JdbcTemplate — which can check what SQL was built but not whether anything
 * accepts it. Since ShardingSphere sits in front of every statement, that difference became a
 * production-only failure: {@code TIMESTAMPADD(SECOND, -?, ...)} parses as a column named
 * SECOND, so every sweep threw "Unknown column 'SECOND' in 'where clause'".
 */
@SpringBootTest
@ActiveProfiles("test")
class BlogLikeOutboxCleanupStatementIT {

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void theCleanupIsAStatementTheDatabaseAccepts() {
        assertThatCode(() -> jdbc.update(BlogLikeOutboxCleanupService.DELETE_PROCESSED_SQL, 86_400, 1))
                .doesNotThrowAnyException();
    }
}
