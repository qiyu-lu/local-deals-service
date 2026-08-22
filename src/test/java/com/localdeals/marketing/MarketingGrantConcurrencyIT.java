package com.localdeals.marketing;

import com.localdeals.dto.AdminPrincipal;
import com.localdeals.dto.VoucherGrantCommand;
import com.localdeals.entity.VoucherGrant;
import com.localdeals.exception.ApiStatusException;
import com.localdeals.service.MarketingAdminService;
import com.localdeals.service.VoucherGrantService;
import com.localdeals.utils.AdminPrincipalHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(classes = M6aPersistenceTestConfiguration.class)
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "spring.datasource.hikari.maximum-pool-size=120",
        "spring.datasource.hikari.minimum-idle=4"
})
@EnabledIfEnvironmentVariable(named = "M6A_ISOLATED", matches = "true")
class MarketingGrantConcurrencyIT {
    private static final long MERCHANT_ID = 1L;
    private static final long CLAIM_USER_ID = 1L;

    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private VoucherGrantService grantService;
    @Autowired private MarketingAdminService marketingAdminService;

    @BeforeEach
    void cleanPreviousFixtures() {
        cleanupFixtures();
    }

    @AfterEach
    void cleanFixtures() {
        AdminPrincipalHolder.remove();
        cleanupFixtures();
    }

    @Test
    void sameUserOneHundredConcurrentClaimsCreateOneGrantAndIncrementOnce() throws Exception {
        Fixture fixture = fixture("same-user", 100, "BOTH", "ALL");
        ConcurrentOutcome outcome = concurrently(100, () -> userClaim(fixture, CLAIM_USER_ID));

        assertThat(outcome.failures).isEmpty();
        assertThat(outcome.successes).hasSize(100);
        assertGrantIdentity(outcome.successes);
        assertInvariants(fixture, 1, 1);
    }

    @Test
    void oneHundredUsersCompeteForQuotaTenAndExactlyTenWin() throws Exception {
        Fixture fixture = fixture("quota", 10, "CLAIM", "ALL");
        List<Long> users = jdbcTemplate.queryForList(
                "SELECT id FROM tb_user ORDER BY id LIMIT 100", Long.class);
        assertThat(users).hasSize(100);
        ConcurrentOutcome outcome = concurrently(users.size(), () -> {
            throw new AssertionError("user supplier must be used by indexed overload");
        }, users, fixture);

        assertThat(outcome.successes).hasSize(10);
        assertThat(outcome.failures).hasSize(90);
        assertThat(outcome.failures).allSatisfy(failure -> {
            assertThat(failure).isInstanceOf(ApiStatusException.class);
            assertThat(((ApiStatusException) failure).getCode()).isEqualTo("CAMPAIGN_QUOTA_EXHAUSTED");
        });
        assertInvariants(fixture, 10, 10);
    }

    @Test
    void userClaimAndAdminGrantRaceOnSameUserStillCreateOneGrant() throws Exception {
        Fixture fixture = fixture("claim-admin", 10, "BOTH", "ALL");
        ConcurrentOutcome outcome = concurrently(2,
                () -> userClaim(fixture, CLAIM_USER_ID),
                () -> adminGrant(fixture, CLAIM_USER_ID));

        assertThat(outcome.failures).isEmpty();
        assertThat(outcome.successes).hasSize(2);
        assertGrantIdentity(outcome.successes);
        assertInvariants(fixture, 1, 1);
    }

    @Test
    void staleRuleVersionHasNoGrantOrQuotaSideEffect() {
        Fixture fixture = fixture("stale-rule", 10, "CLAIM", "ALL");
        VoucherGrantCommand command = userCommand(fixture, CLAIM_USER_ID);
        command.setExpectedRuleVersion(2L);

        assertThatThrownBy(() -> grantService.grant(command))
                .isInstanceOf(ApiStatusException.class)
                .satisfies(error -> assertThat(((ApiStatusException) error).getCode())
                        .isEqualTo("CAMPAIGN_RULE_CHANGED"));
        assertInvariants(fixture, 0, 0);
    }

    @Test
    void tagRemovalRaceUsesTheSameTagMemberLockAndPreservesInvariant() throws Exception {
        Fixture fixture = fixture("tag-race", 10, "BOTH", "MANUAL_TAG");
        jdbcTemplate.update("INSERT INTO tb_marketing_tag_member " +
                        "(merchant_id,tag_id,user_id,status,expire_time,assigned_by) VALUES(?,?,?,?,CURRENT_TIMESTAMP + INTERVAL 1 DAY,?)",
                MERCHANT_ID, fixture.tagId, CLAIM_USER_ID, "ACTIVE", fixture.operatorId);
        ConcurrentOutcome outcome = concurrently(2,
                () -> userClaim(fixture, CLAIM_USER_ID),
                () -> {
                    AdminPrincipal principal = new AdminPrincipal();
                    principal.setAccountId(fixture.operatorId);
                    principal.setMerchantId(MERCHANT_ID);
                    principal.setScopeType(AdminPrincipal.SCOPE_MERCHANT);
                    AdminPrincipalHolder.save(principal);
                    try {
                        marketingAdminService.removeMember(fixture.tagId, CLAIM_USER_ID, MERCHANT_ID);
                        return null;
                    } finally {
                        AdminPrincipalHolder.remove();
                    }
                });

        assertThat(outcome.successes).hasSizeBetween(0, 1);
        assertThat(outcome.failures).allSatisfy(failure -> {
            assertThat(failure).isInstanceOf(ApiStatusException.class);
            assertThat(((ApiStatusException) failure).getCode()).isEqualTo("CAMPAIGN_INELIGIBLE");
        });
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM tb_marketing_tag_member WHERE tag_id=? AND user_id=?",
                String.class, fixture.tagId, CLAIM_USER_ID)).isEqualTo("REMOVED");
        assertInvariants(fixture, outcome.successes.isEmpty() ? 0 : 1,
                outcome.successes.isEmpty() ? 0 : 1);
    }

    private VoucherGrant userClaim(Fixture fixture, long userId) {
        return grantService.grant(userCommand(fixture, userId));
    }

    private VoucherGrant adminGrant(Fixture fixture, long userId) {
        VoucherGrantCommand command = new VoucherGrantCommand();
        command.setCampaignId(fixture.campaignId);
        command.setMerchantId(MERCHANT_ID);
        command.setUserId(userId);
        command.setExpectedRuleVersion(1L);
        command.setOperatorId(fixture.operatorId);
        command.setSource(VoucherGrantCommand.ADMIN_GRANT);
        return grantService.grant(command);
    }

    private VoucherGrantCommand userCommand(Fixture fixture, long userId) {
        VoucherGrantCommand command = new VoucherGrantCommand();
        command.setCampaignId(fixture.campaignId);
        command.setUserId(userId);
        command.setExpectedRuleVersion(1L);
        command.setSource(VoucherGrantCommand.USER_CLAIM);
        return command;
    }

    private ConcurrentOutcome concurrently(int count, Callable<VoucherGrant> first,
            Callable<VoucherGrant> second) throws Exception {
        List<Callable<VoucherGrant>> calls = new ArrayList<>();
        calls.add(first);
        calls.add(second);
        return run(calls);
    }

    private ConcurrentOutcome concurrently(int count, Callable<VoucherGrant> unused,
            List<Long> users, Fixture fixture) throws Exception {
        List<Callable<VoucherGrant>> calls = new ArrayList<>();
        for (Long user : users) {
            calls.add(() -> userClaim(fixture, user));
        }
        return run(calls);
    }

    private ConcurrentOutcome concurrently(int count, Callable<VoucherGrant> call) throws Exception {
        List<Callable<VoucherGrant>> calls = new ArrayList<>();
        for (int i = 0; i < count; i++) calls.add(call);
        return run(calls);
    }

    private ConcurrentOutcome run(List<Callable<VoucherGrant>> calls) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(calls.size());
        CountDownLatch ready = new CountDownLatch(calls.size());
        CountDownLatch start = new CountDownLatch(1);
        List<Future<VoucherGrant>> futures = new ArrayList<>();
        try {
            for (Callable<VoucherGrant> call : calls) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    if (!start.await(30, TimeUnit.SECONDS)) throw new IllegalStateException("start barrier timeout");
                    return call.call();
                }));
            }
            assertThat(ready.await(30, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            ConcurrentOutcome outcome = new ConcurrentOutcome();
            for (Future<VoucherGrant> future : futures) {
                try {
                    VoucherGrant grant = future.get(60, TimeUnit.SECONDS);
                    if (grant != null) outcome.successes.add(grant);
                } catch (ExecutionException failure) {
                    outcome.failures.add(failure.getCause());
                }
            }
            return outcome;
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(60, TimeUnit.SECONDS)).isTrue();
        }
    }

    private void assertGrantIdentity(List<VoucherGrant> grants) {
        assertThat(grants).allSatisfy(grant -> assertThat(grant.getId()).isEqualTo(grants.get(0).getId()));
    }

    private void assertInvariants(Fixture fixture, int expectedGrants, int expectedCount) {
        Integer grants = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM tb_voucher_grant WHERE campaign_id=?", Integer.class, fixture.campaignId);
        Integer count = jdbcTemplate.queryForObject(
                "SELECT granted_count FROM tb_voucher_campaign WHERE id=?", Integer.class, fixture.campaignId);
        assertThat(grants).isEqualTo(expectedGrants);
        assertThat(count).isEqualTo(expectedCount);
        assertThat(count).isEqualTo(grants);
        assertThat(count).isLessThanOrEqualTo(fixture.quota);
    }

    private Fixture fixture(String label, int quota, String mode, String eligibility) {
        String suffix = label + "_" + System.nanoTime();
        String username = "m6a.grant." + suffix;
        String campaignName = "M6A_GRANT_" + suffix;
        String tagCode = "M6A_GRANT_" + suffix;
        jdbcTemplate.update("INSERT INTO tb_admin_account " +
                        "(merchant_id,username,password_hash,display_name,scope_type,status) VALUES(?,?,?,?,?,1)",
                MERCHANT_ID, username, "$2a$10$012345678901234567890u012345678901234567890123456789012",
                username, AdminPrincipal.SCOPE_MERCHANT);
        Long operatorId = jdbcTemplate.queryForObject(
                "SELECT id FROM tb_admin_account WHERE username=?", Long.class, username);
        Long tagId = null;
        if ("MANUAL_TAG".equals(eligibility)) {
            jdbcTemplate.update("INSERT INTO tb_marketing_tag " +
                            "(merchant_id,code,name,status,created_by) VALUES(?,?,?,'ACTIVE',?)",
                    MERCHANT_ID, tagCode, tagCode, operatorId);
            tagId = jdbcTemplate.queryForObject(
                    "SELECT id FROM tb_marketing_tag WHERE merchant_id=? AND code=?", Long.class,
                    MERCHANT_ID, tagCode);
        }
        jdbcTemplate.update("INSERT INTO tb_voucher_campaign " +
                        "(merchant_id,voucher_id,name,grant_mode,eligibility_type,required_tag_id," +
                        "begin_time,end_time,quota_total,granted_count,status,rule_version,created_by) " +
                        "VALUES(1,1,?,?,?, ?,CURRENT_TIMESTAMP - INTERVAL 1 MINUTE," +
                        "CURRENT_TIMESTAMP + INTERVAL 1 DAY,?,0,'ACTIVE',1,?)",
                campaignName, mode, eligibility, tagId, quota, operatorId);
        Long campaignId = jdbcTemplate.queryForObject(
                "SELECT id FROM tb_voucher_campaign WHERE name=?", Long.class, campaignName);
        Fixture fixture = new Fixture(campaignId, tagId, operatorId, quota);
        return fixture;
    }

    private void cleanupFixtures() {
        jdbcTemplate.update("DELETE g FROM tb_voucher_grant g " +
                "JOIN tb_voucher_campaign c ON c.id=g.campaign_id " +
                "WHERE c.name LIKE 'M6A_GRANT_%'");
        jdbcTemplate.update("DELETE m FROM tb_marketing_tag_member m " +
                "JOIN tb_marketing_tag t ON t.id=m.tag_id WHERE t.code LIKE 'M6A_GRANT_%'");
        jdbcTemplate.update("DELETE FROM tb_voucher_campaign WHERE name LIKE 'M6A_GRANT_%'");
        jdbcTemplate.update("DELETE FROM tb_marketing_tag WHERE code LIKE 'M6A_GRANT_%'");
        jdbcTemplate.update("DELETE FROM tb_admin_account WHERE username LIKE 'm6a.grant.%'");
    }

    private static final class Fixture {
        private final Long campaignId;
        private final Long tagId;
        private final Long operatorId;
        private final int quota;

        private Fixture(Long campaignId, Long tagId, Long operatorId, int quota) {
            this.campaignId = campaignId;
            this.tagId = tagId;
            this.operatorId = operatorId;
            this.quota = quota;
        }
    }

    private static final class ConcurrentOutcome {
        private final List<VoucherGrant> successes = new ArrayList<>();
        private final List<Throwable> failures = new ArrayList<>();
    }
}
