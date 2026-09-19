package com.localdeals.trade.service;

import com.localdeals.merchant.audit.AdminAuditLog;
import com.localdeals.merchant.audit.AdminAuditService;
import com.localdeals.merchant.dto.AdminPrincipal;
import com.localdeals.trade.entity.TradeOrder;
import com.localdeals.trade.mq.SeckillOrderMessage;
import com.localdeals.trade.testsupport.TradeFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Back-office order list, audit log storage and the M2 permissions, on MySQL. */
@SpringBootTest
@ActiveProfiles("test")
class AdminTradeIT {

    private static final long BASE = 9_206_000L;
    private static final long OTHER_BASE = 9_207_000L;

    @Autowired
    private IVoucherOrderService orderService;
    @Autowired
    private AdminOrderService adminOrderService;
    @Autowired
    private AdminAuditService auditService;
    @Autowired
    private JdbcTemplate jdbc;

    private TradeFixture mine;
    private TradeFixture other;

    @BeforeEach
    void setUp() {
        mine = new TradeFixture(jdbc, BASE).create(10);
        other = new TradeFixture(jdbc, OTHER_BASE).create(10);
    }

    @AfterEach
    void tearDown() {
        mine.delete();
        other.delete();
    }

    @Test
    void staffCanVerifyButOnlyOwnersAndPlatformReadTheAuditLog() {
        assertThat(rolesWith("coupon:verify")).containsExactlyInAnyOrder("PLATFORM_ADMIN", "MERCHANT_OWNER", "MERCHANT_STAFF");
        assertThat(rolesWith("audit:read")).containsExactlyInAnyOrder("PLATFORM_ADMIN", "MERCHANT_OWNER");
    }

    @Test
    void aMerchantSeesOnlyItsOwnOrdersAndCanFilterByStatus() {
        orderService.createPendingOrder(new SeckillOrderMessage(mine.voucherId, BASE + 1, BASE + 1));
        orderService.createPendingOrder(new SeckillOrderMessage(other.voucherId, OTHER_BASE + 1, OTHER_BASE + 1));

        List<TradeOrder> orders = adminOrderService.list(merchant(mine.merchantId), null, 1, 100).getRecords();

        assertThat(orders).extracting(TradeOrder::getOrderNo).containsExactly(BASE + 1);
        assertThat(adminOrderService.list(merchant(mine.merchantId), "PAID", 1, 100).getRecords()).isEmpty();
        assertThat(adminOrderService.list(platform(), null, 1, 100).getRecords())
                .extracting(TradeOrder::getOrderNo).contains(BASE + 1, OTHER_BASE + 1);
    }

    @Test
    void auditRecordsAreScopedToTheMerchant() {
        auditService.record(log(mine.merchantId, "COUPON_VERIFY"));
        auditService.record(log(other.merchantId, "COUPON_VERIFY"));

        assertThat(auditService.list(merchant(mine.merchantId), 1, 100).getRecords())
                .extracting(AdminAuditLog::getMerchantId).containsOnly(mine.merchantId);
    }

    private List<String> rolesWith(String permission) {
        return jdbc.queryForList("SELECT r.code FROM tb_admin_role r JOIN tb_admin_role_permission p " +
                "ON p.role_id = r.id WHERE p.permission_code = ?", String.class, permission);
    }

    private static AdminAuditLog log(long merchantId, String action) {
        AdminAuditLog log = new AdminAuditLog();
        log.setAccountId(1L);
        log.setMerchantId(merchantId);
        log.setUsername("it");
        log.setAction(action);
        log.setResult(AdminAuditLog.SUCCESS);
        return log;
    }

    private static AdminPrincipal merchant(long merchantId) {
        AdminPrincipal principal = new AdminPrincipal();
        principal.setAccountId(1L);
        principal.setMerchantId(merchantId);
        principal.setScopeType(AdminPrincipal.SCOPE_MERCHANT);
        return principal;
    }

    private static AdminPrincipal platform() {
        AdminPrincipal principal = new AdminPrincipal();
        principal.setAccountId(1L);
        principal.setScopeType(AdminPrincipal.SCOPE_PLATFORM);
        return principal;
    }
}
