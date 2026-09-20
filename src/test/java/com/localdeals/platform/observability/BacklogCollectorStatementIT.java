package com.localdeals.platform.observability;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * The backlog sampler runs on a schedule, swallows its own failures so a metric never takes the
 * application down, and uses FORCE INDEX — a construct the SQL parser in front of the database
 * now has to accept. Nothing else executes these two statements: the collector's own IT is
 * environment-gated, and catching the exception means calling collectOutbox() would report
 * nothing either way. So they are run here, directly.
 */
@SpringBootTest
@ActiveProfiles("test")
class BacklogCollectorStatementIT {

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void theOutboxHeadSampleIsAStatementTheDatabaseAccepts() {
        assertThatCode(() -> jdbc.queryForList(ReliabilityBacklogCollector.OUTBOX_HEAD_SQL))
                .doesNotThrowAnyException();
    }

    @Test
    void theOutboxCreatedAtLookupIsAStatementTheDatabaseAccepts() {
        assertThatCode(() -> jdbc.queryForList(ReliabilityBacklogCollector.OUTBOX_CREATED_AT_SQL, 1L))
                .doesNotThrowAnyException();
    }
}
