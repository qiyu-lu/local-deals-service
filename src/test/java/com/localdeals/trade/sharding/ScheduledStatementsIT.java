package com.localdeals.trade.sharding;

import com.localdeals.trade.service.CouponService;
import com.localdeals.trade.service.OrderTimeoutScanner;
import com.localdeals.trade.service.RefundService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Every statement a scheduler issues, run once against the sharded data source.
 *
 * <p>These sweeps are switched off in tests so nothing drains shared data in the background, and
 * what coverage they had was unit tests holding a mocked JdbcTemplate — which can say what SQL
 * was sent but never whether anything accepts it. With ShardingSphere in front of the database
 * that is no longer a small gap: the parser sees every statement, including the ones on tables
 * that were never sharded. {@code TIMESTAMPADD(SECOND, -?, ...)} is the example that got here
 * first; it parses as a column named SECOND and threw once per sweep, in production only.
 * That one lives with its own service, in BlogLikeOutboxCleanupStatementIT.</p>
 */
@SpringBootTest
@ActiveProfiles("test")
class ScheduledStatementsIT {

    @Autowired
    private OrderTimeoutScanner orderTimeoutScanner;
    @Autowired
    private RefundService refundService;
    @Autowired
    private CouponService couponService;

    @Test
    void theOverdueOrderAndPendingReleaseScansAreStatementsTheDatabaseAccepts() {
        assertThatCode(() -> orderTimeoutScanner.scanOnce()).doesNotThrowAnyException();
    }

    @Test
    void theStaleRefundSweepIsAStatementTheDatabaseAccepts() {
        assertThatCode(() -> refundService.retryStale(1)).doesNotThrowAnyException();
    }

    @Test
    void theCouponExpirySweepIsAStatementTheDatabaseAccepts() {
        assertThatCode(() -> couponService.expireDue(1)).doesNotThrowAnyException();
    }
}
