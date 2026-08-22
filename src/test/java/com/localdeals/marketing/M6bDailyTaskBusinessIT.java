package com.localdeals.marketing;

import com.localdeals.dto.AdminPrincipal;
import com.localdeals.dto.VoucherCampaignRequest;
import com.localdeals.dto.VoucherCampaignStatusRequest;
import com.localdeals.dto.VoucherCampaignUserView;
import com.localdeals.dto.VoucherGrantClaimRequest;
import com.localdeals.dto.VoucherGrantCommand;
import com.localdeals.dto.VoucherGrantUserView;
import com.localdeals.entity.AdminAccount;
import com.localdeals.entity.Merchant;
import com.localdeals.entity.Shop;
import com.localdeals.entity.SignRecord;
import com.localdeals.entity.Voucher;
import com.localdeals.entity.VoucherCampaign;
import com.localdeals.entity.VoucherGrant;
import com.localdeals.exception.ApiStatusException;
import com.localdeals.mapper.AdminAccountMapper;
import com.localdeals.mapper.MerchantMapper;
import com.localdeals.mapper.ShopMapper;
import com.localdeals.mapper.SignMapper;
import com.localdeals.mapper.VoucherMapper;
import com.localdeals.service.BusinessDateProvider;
import com.localdeals.service.MarketingAdminService;
import com.localdeals.service.VoucherCampaignUserService;
import com.localdeals.service.VoucherGrantService;
import com.localdeals.utils.AdminPrincipalHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
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

@SpringBootTest(classes = M6bPersistenceTestConfiguration.class)
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "spring.datasource.hikari.maximum-pool-size=120",
        "spring.datasource.hikari.minimum-idle=4"
})
@EnabledIfEnvironmentVariable(named = "M6B_ISOLATED", matches = "true")
class M6bDailyTaskBusinessIT {
    private static final long MERCHANT_ID = 1L;
    private static final Instant DAY_ONE = Instant.parse("2026-08-21T16:00:00Z");
    private static final Instant DAY_TWO = Instant.parse("2026-08-22T16:00:00Z");
    private static final String FIXTURE_PREFIX = "M6B_TASK_";
    private static final String USER_PHONE_PREFIX = "139";
    private static final String ACCOUNT_PREFIX = "m6b.task.";

    @DynamicPropertySource
    static void registerM6bDatasource(DynamicPropertyRegistry registry) {
        M6bDatasourceGuard.register(registry);
    }

    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private SignMapper signMapper;
    @Autowired private VoucherGrantService grantService;
    @Autowired private MarketingAdminService marketingAdminService;
    @Autowired private VoucherCampaignUserService campaignUserService;
    @Autowired private BusinessDateProvider businessDateProvider;
    @Autowired private MutableBusinessClock businessClock;
    @Autowired private MerchantMapper merchantMapper;
    @Autowired private AdminAccountMapper adminAccountMapper;
    @Autowired private ShopMapper shopMapper;
    @Autowired private VoucherMapper voucherMapper;

    @BeforeEach
    void cleanPreviousFixtures() {
        businessClock.setInstant(DAY_ONE);
        AdminPrincipalHolder.remove();
        cleanupFixtures();
    }

    @AfterEach
    void cleanFixtures() {
        AdminPrincipalHolder.remove();
        cleanupFixtures();
    }

    @Test
    void oneHundredConcurrentSignsAndTaskRewardsAreOnePerBusinessDayAndCrossMidnight() throws Exception {
        Long userId = createUser("flow");
        Long operatorId = createAccount(MERCHANT_ID, "flow");
        VoucherCampaign campaign = createActiveCampaign(operatorId, "flow", "TASK", 10, 1L);

        assertThat(concurrently(100, () -> {
            sign(userId);
            return null;
        }).failures).isEmpty();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM tb_sign WHERE user_id=? AND `date`=?", Integer.class,
                userId, businessDateProvider.today())).isEqualTo(1);

        ConcurrentOutcome<VoucherGrantUserView> rewards = concurrently(100,
                () -> campaignUserService.taskReward(campaign.getId(), expected(campaign), userId));
        assertThat(rewards.failures).isEmpty();
        assertThat(rewards.successes).hasSize(100);
        assertThat(rewards.successes).allSatisfy(grant ->
                assertThat(grant.getId()).isEqualTo(rewards.successes.get(0).getId()));
        assertGrantInvariant(campaign.getId(), 1, 1, 1);

        VoucherCampaignUserView dayOneView = campaignView(campaign.getId(), userId);
        assertThat(dayOneView.getGrantMode()).isEqualTo("TASK");
        assertThat(dayOneView.getAlreadyGranted()).isTrue();

        businessClock.setInstant(DAY_TWO);
        sign(userId);
        VoucherGrantUserView nextDay = campaignUserService.taskReward(
                campaign.getId(), expected(campaign), userId);
        assertThat(nextDay.getId()).isNotEqualTo(rewards.successes.get(0).getId());
        assertGrantInvariant(campaign.getId(), 2, 2, 2);
        assertThat(jdbcTemplate.queryForList(
                "SELECT idempotency_key FROM tb_voucher_grant WHERE campaign_id=? ORDER BY id",
                String.class, campaign.getId()))
                .containsExactly("TASK_REWARD:DAILY_SIGN_IN:2026-08-22",
                        "TASK_REWARD:DAILY_SIGN_IN:2026-08-23");
        assertThat(campaignView(campaign.getId(), userId).getAlreadyGranted()).isTrue();
    }

    @Test
    void taskFailuresAndTaskModeAdminPathHaveNoGrantOrQuotaSideEffect() {
        Long userId = createUser("failure");
        Long operatorId = createAccount(MERCHANT_ID, "failure");
        VoucherCampaign task = createActiveCampaign(operatorId, "failure-task", "TASK", 10, 1L);

        assertThatThrownBy(() -> campaignUserService.taskReward(
                task.getId(), expected(task), userId))
                .isInstanceOf(ApiStatusException.class)
                .satisfies(error -> assertThat(((ApiStatusException) error).getCode())
                        .isEqualTo("TASK_NOT_COMPLETED"));
        assertGrantInvariant(task.getId(), 0, 0, 0);

        sign(userId);
        VoucherGrantClaimRequest stale = expected(task);
        stale.setExpectedRuleVersion(task.getRuleVersion() + 1);
        assertThatThrownBy(() -> campaignUserService.taskReward(task.getId(), stale, userId))
                .isInstanceOf(ApiStatusException.class)
                .satisfies(error -> assertThat(((ApiStatusException) error).getCode())
                        .isEqualTo("CAMPAIGN_RULE_CHANGED"));
        assertGrantInvariant(task.getId(), 0, 0, 0);

        VoucherGrantCommand manual = new VoucherGrantCommand();
        manual.setCampaignId(task.getId());
        manual.setMerchantId(MERCHANT_ID);
        manual.setUserId(userId);
        manual.setExpectedRuleVersion(task.getRuleVersion());
        manual.setOperatorId(operatorId);
        manual.setSource(VoucherGrantCommand.ADMIN_GRANT);
        assertThatThrownBy(() -> grantService.grant(manual))
                .isInstanceOf(ApiStatusException.class)
                .satisfies(error -> assertThat(((ApiStatusException) error).getCode())
                        .isEqualTo("CAMPAIGN_GRANT_MODE_UNSUPPORTED"));
        assertGrantInvariant(task.getId(), 0, 0, 0);

        VoucherCampaign claim = createActiveCampaign(operatorId, "failure-claim", "CLAIM", 10, 1L);
        assertThatThrownBy(() -> campaignUserService.taskReward(
                claim.getId(), expected(claim), userId))
                .isInstanceOf(ApiStatusException.class)
                .satisfies(error -> assertThat(((ApiStatusException) error).getCode())
                        .isEqualTo("CAMPAIGN_GRANT_MODE_UNSUPPORTED"));
        assertGrantInvariant(claim.getId(), 0, 0, 0);
    }

    @Test
    void quotaExhaustionAndM6aOnceRaceRemainSideEffectSafe() throws Exception {
        Long userA = createUser("quota-a");
        Long userB = createUser("quota-b");
        Long operatorId = createAccount(MERCHANT_ID, "quota");
        VoucherCampaign task = createActiveCampaign(operatorId, "quota-task", "TASK", 1, 1L);
        sign(userA);
        sign(userB);
        campaignUserService.taskReward(task.getId(), expected(task), userA);
        assertThatThrownBy(() -> campaignUserService.taskReward(task.getId(), expected(task), userB))
                .isInstanceOf(ApiStatusException.class)
                .satisfies(error -> assertThat(((ApiStatusException) error).getCode())
                        .isEqualTo("CAMPAIGN_QUOTA_EXHAUSTED"));
        assertGrantInvariant(task.getId(), 1, 1, 1);

        VoucherCampaign once = createActiveCampaign(operatorId, "once-race", "BOTH", 10, 1L);
        ConcurrentOutcome<VoucherGrant> outcome = concurrently(2,
                () -> grantService.grant(userClaim(once, userA)),
                () -> grantService.grant(adminGrant(once, userA, operatorId)));
        assertThat(outcome.failures).isEmpty();
        assertThat(outcome.successes).hasSize(2);
        assertThat(outcome.successes.get(0).getId()).isEqualTo(outcome.successes.get(1).getId());
        assertGrantInvariant(once.getId(), 1, 1, 1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT idempotency_key FROM tb_voucher_grant WHERE campaign_id=?", String.class,
                once.getId())).isEqualTo("ONCE");
        assertThat(campaignView(once.getId(), userA).getAlreadyGranted()).isTrue();
    }

    @Test
    void merchantAIsForbiddenFromOperatingMerchantBTaskCampaign() {
        Merchant merchantB = new Merchant();
        merchantB.setCode(FIXTURE_PREFIX + "MERCHANT_B");
        merchantB.setName("M6B Merchant B");
        merchantB.setStatus(1);
        merchantMapper.insert(merchantB);
        Long accountA = createAccount(MERCHANT_ID, "scope-a");
        Long accountB = createAccount(merchantB.getId(), "scope-b");
        Shop shopB = createShop(merchantB.getId());
        Voucher voucherB = createVoucher(shopB.getId());

        AdminPrincipalHolder.save(merchantPrincipal(accountB, merchantB.getId()));
        VoucherCampaign campaignB = createActiveCampaign(accountB, merchantB.getId(),
                "scope-b", "TASK", 5, voucherB.getId());
        AdminPrincipalHolder.save(merchantPrincipal(accountA, MERCHANT_ID));

        VoucherCampaignStatusRequest request = new VoucherCampaignStatusRequest();
        request.setMerchantId(merchantB.getId());
        request.setExpectedStatus(campaignB.getStatus());
        request.setExpectedRuleVersion(campaignB.getRuleVersion());
        request.setStatus("PAUSED");
        assertThatThrownBy(() -> marketingAdminService.changeStatus(campaignB.getId(), request))
                .isInstanceOf(ApiStatusException.class)
                .hasMessage("禁止跨商户操作");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM tb_voucher_campaign WHERE id=?", String.class,
                campaignB.getId())).isEqualTo("ACTIVE");
        assertGrantInvariant(campaignB.getId(), 0, 0, 0);
    }

    private VoucherGrantCommand userClaim(VoucherCampaign campaign, Long userId) {
        VoucherGrantCommand command = new VoucherGrantCommand();
        command.setCampaignId(campaign.getId());
        command.setUserId(userId);
        command.setExpectedRuleVersion(campaign.getRuleVersion());
        command.setSource(VoucherGrantCommand.USER_CLAIM);
        return command;
    }

    private VoucherGrantCommand adminGrant(VoucherCampaign campaign, Long userId, Long operatorId) {
        VoucherGrantCommand command = userClaim(campaign, userId);
        command.setMerchantId(MERCHANT_ID);
        command.setOperatorId(operatorId);
        command.setSource(VoucherGrantCommand.ADMIN_GRANT);
        return command;
    }

    private VoucherCampaign createActiveCampaign(Long operatorId, String name,
            String grantMode, int quota, Long voucherId) {
        return createActiveCampaign(operatorId, MERCHANT_ID, name, grantMode, quota, voucherId);
    }

    private VoucherCampaign createActiveCampaign(Long operatorId, Long merchantId, String name,
            String grantMode, int quota, Long voucherId) {
        AdminPrincipalHolder.save(merchantPrincipal(operatorId, merchantId));
        VoucherCampaignRequest request = new VoucherCampaignRequest();
        request.setVoucherId(voucherId);
        request.setName(FIXTURE_PREFIX + name + "_" + System.nanoTime());
        request.setGrantMode(grantMode);
        request.setEligibilityType("ALL");
        LocalDateTime databaseNow = jdbcTemplate.queryForObject(
                "SELECT CURRENT_TIMESTAMP", LocalDateTime.class);
        request.setBeginTime(databaseNow.minusMinutes(5));
        request.setEndTime(databaseNow.plusDays(3));
        request.setQuotaTotal(quota);
        VoucherCampaign draft = marketingAdminService.createCampaign(request);

        VoucherCampaignStatusRequest status = new VoucherCampaignStatusRequest();
        status.setExpectedStatus(draft.getStatus());
        status.setExpectedRuleVersion(draft.getRuleVersion());
        status.setStatus("ACTIVE");
        return marketingAdminService.changeStatus(draft.getId(), status);
    }

    private VoucherGrantClaimRequest expected(VoucherCampaign campaign) {
        VoucherGrantClaimRequest request = new VoucherGrantClaimRequest();
        request.setExpectedRuleVersion(campaign.getRuleVersion());
        return request;
    }

    private VoucherCampaignUserView campaignView(Long campaignId, Long userId) {
        List<VoucherCampaignUserView> views = campaignUserService.listForShop(1L, userId);
        return views.stream().filter(view -> campaignId.equals(view.getId())).findFirst()
                .orElseThrow(() -> new AssertionError("task campaign view missing"));
    }

    private void sign(Long userId) {
        SignRecord record = new SignRecord();
        LocalDate today = businessDateProvider.today();
        record.setUserId(userId);
        record.setYear(today.getYear());
        record.setMonth(today.getMonthValue());
        record.setDate(today);
        try {
            signMapper.insert(record);
        } catch (org.springframework.dao.DuplicateKeyException duplicate) {
            // The unique key makes concurrent sign-in idempotent.
        }
    }

    private Long createUser(String name) {
        String suffix = String.format("%08d", Math.abs(System.nanoTime() % 100000000L));
        String phone = USER_PHONE_PREFIX + suffix;
        jdbcTemplate.update("INSERT INTO tb_user(phone,nick_name) VALUES(?,?)", phone,
                FIXTURE_PREFIX + name);
        return jdbcTemplate.queryForObject("SELECT id FROM tb_user WHERE phone=?", Long.class, phone);
    }

    private Long createAccount(Long merchantId, String name) {
        String username = ACCOUNT_PREFIX + name + "." + System.nanoTime();
        jdbcTemplate.update("INSERT INTO tb_admin_account " +
                        "(merchant_id,username,password_hash,display_name,scope_type,status) VALUES(?,?,?,?,?,1)",
                merchantId, username,
                "$2a$10$012345678901234567890u012345678901234567890123456789012",
                username, AdminPrincipal.SCOPE_MERCHANT);
        return jdbcTemplate.queryForObject("SELECT id FROM tb_admin_account WHERE username=?",
                Long.class, username);
    }

    private Shop createShop(Long merchantId) {
        Shop shop = new Shop();
        shop.setMerchantId(merchantId);
        shop.setName(FIXTURE_PREFIX + "SHOP_" + System.nanoTime());
        shop.setTypeId(1L);
        shop.setImages("");
        shop.setAddress("M6B isolated");
        shop.setX(120.0);
        shop.setY(30.0);
        shop.setSold(0);
        shop.setComments(0);
        shop.setScore(0);
        shopMapper.insert(shop);
        return shop;
    }

    private Voucher createVoucher(Long shopId) {
        Voucher voucher = new Voucher();
        voucher.setShopId(shopId);
        voucher.setTitle(FIXTURE_PREFIX + "VOUCHER_" + System.nanoTime());
        voucher.setPayValue(800L);
        voucher.setActualValue(1000L);
        voucher.setType(0);
        voucher.setStatus(1);
        voucherMapper.insert(voucher);
        return voucher;
    }

    private AdminPrincipal merchantPrincipal(Long accountId, Long merchantId) {
        AdminPrincipal principal = new AdminPrincipal();
        principal.setAccountId(accountId);
        principal.setMerchantId(merchantId);
        principal.setScopeType(AdminPrincipal.SCOPE_MERCHANT);
        return principal;
    }

    private void assertGrantInvariant(Long campaignId, int grants, int count, int expectedKeys) {
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM tb_voucher_grant WHERE campaign_id=?", Integer.class, campaignId))
                .isEqualTo(grants);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT granted_count FROM tb_voucher_campaign WHERE id=?", Integer.class, campaignId))
                .isEqualTo(count);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM tb_voucher_grant WHERE campaign_id=? AND idempotency_key IS NOT NULL",
                Integer.class, campaignId)).isEqualTo(expectedKeys);
    }

    private <T> ConcurrentOutcome<T> concurrently(int count, Callable<T> call) throws Exception {
        List<Callable<T>> calls = new ArrayList<>();
        for (int i = 0; i < count; i++) calls.add(call);
        return run(calls);
    }

    private <T> ConcurrentOutcome<T> concurrently(int count, Callable<T> first,
            Callable<T> second) throws Exception {
        List<Callable<T>> calls = new ArrayList<>();
        calls.add(first);
        calls.add(second);
        return run(calls);
    }

    private <T> ConcurrentOutcome<T> run(List<Callable<T>> calls) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(calls.size());
        CountDownLatch ready = new CountDownLatch(calls.size());
        CountDownLatch start = new CountDownLatch(1);
        List<Future<T>> futures = new ArrayList<>();
        try {
            for (Callable<T> call : calls) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    if (!start.await(30, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("start barrier timeout");
                    }
                    return call.call();
                }));
            }
            assertThat(ready.await(30, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            ConcurrentOutcome<T> outcome = new ConcurrentOutcome<>();
            for (Future<T> future : futures) {
                try {
                    outcome.successes.add(future.get(90, TimeUnit.SECONDS));
                } catch (ExecutionException failure) {
                    outcome.failures.add(failure.getCause());
                }
            }
            return outcome;
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(90, TimeUnit.SECONDS)).isTrue();
        }
    }

    private void cleanupFixtures() {
        jdbcTemplate.update("DELETE g FROM tb_voucher_grant g JOIN tb_voucher_campaign c " +
                "ON c.id=g.campaign_id WHERE c.name LIKE ?", FIXTURE_PREFIX + "%");
        jdbcTemplate.update("DELETE FROM tb_voucher_campaign WHERE name LIKE ?", FIXTURE_PREFIX + "%");
        jdbcTemplate.update("DELETE s FROM tb_sign s JOIN tb_user u ON u.id=s.user_id " +
                "WHERE u.nick_name LIKE ?", FIXTURE_PREFIX + "%");
        jdbcTemplate.update("DELETE FROM tb_user WHERE nick_name LIKE ?", FIXTURE_PREFIX + "%");
        jdbcTemplate.update("DELETE FROM tb_admin_account WHERE username LIKE ?", ACCOUNT_PREFIX + "%");
        jdbcTemplate.update("DELETE v FROM tb_voucher v JOIN tb_shop s ON s.id=v.shop_id " +
                "WHERE s.name LIKE ?", FIXTURE_PREFIX + "%");
        jdbcTemplate.update("DELETE FROM tb_shop WHERE name LIKE ?", FIXTURE_PREFIX + "%");
        jdbcTemplate.update("DELETE FROM tb_merchant WHERE code LIKE ?", FIXTURE_PREFIX + "%");
    }

    private static final class ConcurrentOutcome<T> {
        private final List<T> successes = new ArrayList<>();
        private final List<Throwable> failures = new ArrayList<>();
    }
}
