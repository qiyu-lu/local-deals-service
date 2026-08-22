package com.localdeals.marketing;

import com.localdeals.dto.AdminPrincipal;
import com.localdeals.dto.MarketingTagMemberRequest;
import com.localdeals.dto.MarketingTagRequest;
import com.localdeals.dto.VoucherBatchJobCreateRequest;
import com.localdeals.dto.VoucherCampaignRequest;
import com.localdeals.dto.VoucherCampaignStatusRequest;
import com.localdeals.dto.VoucherGrantClaimRequest;
import com.localdeals.dto.VoucherGrantCommand;
import com.localdeals.entity.MarketingTag;
import com.localdeals.entity.VoucherBatchItem;
import com.localdeals.entity.VoucherBatchJob;
import com.localdeals.entity.VoucherCampaign;
import com.localdeals.exception.ApiStatusException;
import com.localdeals.mapper.VoucherBatchItemMapper;
import com.localdeals.service.MarketingAdminService;
import com.localdeals.service.VoucherBatchJobService;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(classes = M6cPersistenceTestConfiguration.class)
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "M6C_ISOLATED", matches = "true")
class M6cBatchBusinessIT {
    private static final long MERCHANT_ID = 1L;
    private static final long VOUCHER_ID = 1L;
    private static final String FIXTURE_PREFIX = "M6C_IT_";
    private static final String ACCOUNT_PREFIX = "m6c.it.";
    private static final AtomicLong ORDER_ID = new AtomicLong(910000000000000000L);

    @DynamicPropertySource
    static void registerM6cDatasource(DynamicPropertyRegistry registry) {
        M6cDatasourceGuard.register(registry);
    }

    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private MarketingAdminService marketingAdminService;
    @Autowired private VoucherBatchJobService batchJobService;
    @Autowired private VoucherBatchItemMapper batchItemMapper;
    @Autowired private VoucherCampaignUserService campaignUserService;
    @Autowired private VoucherGrantService grantService;

    @BeforeEach
    void cleanBefore() {
        AdminPrincipalHolder.remove();
        cleanupFixtures();
    }

    @AfterEach
    void cleanAfter() {
        AdminPrincipalHolder.remove();
        cleanupFixtures();
    }

    @Test
    void concurrentRequestIdCreatesOneJobAndSnapshotIsImmutableButEligibilityIsRechecked() throws Exception {
        Long operatorId = createAccount(MERCHANT_ID, "request");
        AdminPrincipalHolder.save(merchantPrincipal(operatorId, MERCHANT_ID));
        List<Long> initialUsers = createUsers(3, "snapshot");
        MarketingTag tag = createTag("SNAPSHOT");
        addMembers(tag, initialUsers);
        VoucherCampaign campaign = createActiveCampaign(tag.getId(), "snapshot", "BOTH", 10);

        List<VoucherBatchJob> jobs = concurrently(8, () -> {
            AdminPrincipalHolder.save(merchantPrincipal(operatorId, MERCHANT_ID));
            try {
                return batchJobService.create(campaign.getId(), batchRequest("same-request", campaign));
            } finally {
                AdminPrincipalHolder.remove();
            }
        });
        assertThat(jobs).hasSize(8);
        // Every caller must observe the same durable id, not merely avoid a duplicate exception.
        assertThat(jobs).extracting(VoucherBatchJob::getId).containsOnly(jobs.get(0).getId());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM tb_voucher_batch_job WHERE merchant_id=? AND request_id=?",
                Integer.class, MERCHANT_ID, "same-request")).isEqualTo(1);

        AdminPrincipalHolder.save(merchantPrincipal(operatorId, MERCHANT_ID));
        assertThat(batchJobService.snapshotOne()).isEqualTo(initialUsers.size());
        VoucherBatchJob job = batchJobService.get(jobs.get(0).getId(), null);

        Long addedAfterSnapshot = createUsers(1, "late").get(0);
        addMembers(tag, Collections.singletonList(addedAfterSnapshot));
        marketingAdminService.removeMember(tag.getId(), initialUsers.get(2), null);

        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM tb_voucher_batch_item WHERE job_id=?", Integer.class, job.getId()))
                .isEqualTo(initialUsers.size());
        assertThat(jdbcTemplate.queryForList(
                "SELECT user_id FROM tb_voucher_batch_item WHERE job_id=? ORDER BY user_id", Long.class, job.getId()))
                .containsExactlyElementsOf(initialUsers.stream().sorted().collect(java.util.stream.Collectors.toList()));

        processToEnd(job.getId(), 2);
        assertThat(itemCount(job.getId(), "GRANTED")).isEqualTo(2);
        assertThat(itemCount(job.getId(), "SKIPPED")).isEqualTo(1);
        assertThat(itemCount(job.getId(), "PENDING")).isZero();
        assertThat(grantCount(campaign.getId())).isEqualTo(2);
        assertThat(outboxCount(campaign.getId())).isEqualTo(2);
        assertThat(itemUserStatus(job.getId(), addedAfterSnapshot)).isNull();
    }

    @Test
    void oneHundredItemsUseSmallBatchesAndPauseResumeConvergesToGrantAndQuotaInvariant() {
        Long operatorId = createAccount(MERCHANT_ID, "hundred");
        AdminPrincipalHolder.save(merchantPrincipal(operatorId, MERCHANT_ID));
        List<Long> users = createUsers(100, "hundred");
        MarketingTag tag = createTag("HUNDRED");
        addMembers(tag, users);
        VoucherCampaign campaign = createActiveCampaign(tag.getId(), "hundred", "ADMIN", 100);
        VoucherBatchJob job = batchJobService.create(campaign.getId(), batchRequest("hundred", campaign));
        assertThat(batchJobService.snapshotOne()).isEqualTo(100);

        long convergenceStarted = System.nanoTime();
        VoucherBatchJobService.BatchRunResult first = batchJobService.processNextBatch(17);
        assertThat(first.getProcessed()).isEqualTo(17);
        assertThat(batchJobService.pause(job.getId(), null).getStatus()).isEqualTo("PAUSED");
        assertThat(batchJobService.processNextBatch(17).getProcessed()).isZero();
        assertThat(itemCount(job.getId(), "PENDING")).isEqualTo(83);

        batchJobService.resume(job.getId(), null);
        int resumedBatches = processToEnd(job.getId(), 17);
        VoucherBatchJob finished = batchJobService.get(job.getId(), null);
        assertThat(finished.getStatus()).isEqualTo("COMPLETED");
        assertThat(itemCount(job.getId(), "GRANTED")).isEqualTo(100);
        assertThat(itemCount(job.getId(), "IDEMPOTENT")).isZero();
        assertThat(itemCount(job.getId(), "SKIPPED")).isZero();
        assertThat(itemCount(job.getId(), "FAILED")).isZero();
        assertThat(grantCount(campaign.getId())).isEqualTo(100);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT granted_count FROM tb_voucher_campaign WHERE id=?", Integer.class, campaign.getId()))
                .isEqualTo(100);
        assertThat(outboxCount(campaign.getId())).isEqualTo(100);
        long convergenceMillis = (System.nanoTime() - convergenceStarted) / 1_000_000L;
        System.out.println("M6C_BATCH_METRIC target=100 granted=100 idempotent=0 skipped=0 failed=0 " +
                "quota_remaining=0 worker_batches=" + (resumedBatches + 1) +
                " convergence_ms=" + convergenceMillis + " item_latency_p99_ms=NA rate_limited=NA");
    }

    @Test
    void earlyClaimAndTwoJobsShareTheSameOnceGrantAndOnlyNewFactsCreateOutboxRows() {
        Long operatorId = createAccount(MERCHANT_ID, "once");
        AdminPrincipalHolder.save(merchantPrincipal(operatorId, MERCHANT_ID));
        List<Long> users = createUsers(2, "once");
        MarketingTag tag = createTag("ONCE");
        addMembers(tag, users);
        VoucherCampaign campaign = createActiveCampaign(tag.getId(), "once", "BOTH", 10);

        AdminPrincipalHolder.remove();
        VoucherGrantClaimRequest claim = new VoucherGrantClaimRequest();
        claim.setExpectedRuleVersion(campaign.getRuleVersion());
        Long preclaimedGrantId = campaignUserService.claim(campaign.getId(), claim, users.get(0)).getId();

        AdminPrincipalHolder.save(merchantPrincipal(operatorId, MERCHANT_ID));
        VoucherBatchJob first = batchJobService.create(campaign.getId(), batchRequest("once-1", campaign));
        VoucherBatchJob second = batchJobService.create(campaign.getId(), batchRequest("once-2", campaign));
        assertThat(batchJobService.snapshotOne()).isEqualTo(2);
        assertThat(batchJobService.snapshotOne()).isEqualTo(2);
        processToEnd(first.getId(), 2);
        processToEnd(second.getId(), 2);

        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM tb_voucher_grant WHERE campaign_id=? AND user_id=? AND idempotency_key='ONCE'",
                Integer.class, campaign.getId(), users.get(0))).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT id FROM tb_voucher_grant WHERE campaign_id=? AND user_id=?",
                Long.class, campaign.getId(), users.get(0))).isEqualTo(preclaimedGrantId);
        assertThat(grantCount(campaign.getId())).isEqualTo(2);
        assertThat(outboxCount(campaign.getId())).isEqualTo(2);
        assertThat(itemUserStatus(first.getId(), users.get(0))).isEqualTo("IDEMPOTENT");
        assertThat(itemUserStatus(second.getId(), users.get(0))).isEqualTo("IDEMPOTENT");
        assertThat(itemCount(first.getId(), "GRANTED") + itemCount(first.getId(), "IDEMPOTENT")).isEqualTo(2);
        assertThat(itemCount(second.getId(), "GRANTED") + itemCount(second.getId(), "IDEMPOTENT")).isEqualTo(2);
    }

    @Test
    void retryResetsOnlyTechnicalFailuresAndRuleTagQuotaAndScopeFailuresRemainStable() {
        Long operatorId = createAccount(MERCHANT_ID, "retry");
        AdminPrincipalHolder.save(merchantPrincipal(operatorId, MERCHANT_ID));
        List<Long> users = createUsers(2, "retry");
        MarketingTag tag = createTag("RETRY");
        addMembers(tag, users);
        VoucherCampaign campaign = createActiveCampaign(tag.getId(), "retry", "ADMIN", 10);
        VoucherBatchJob job = batchJobService.create(campaign.getId(), batchRequest("retry", campaign));
        batchJobService.snapshotOne();
        List<VoucherBatchItem> items = batchItemMapper.selectPageScoped(job.getId(), MERCHANT_ID, 10, 0);
        batchItemMapper.markOutcome(items.get(0).getId(), "FAILED", null, "TECHNICAL_ERROR", "技术错误");
        batchItemMapper.markOutcome(items.get(1).getId(), "SKIPPED", null,
                "CAMPAIGN_INELIGIBLE", "当前不满足活动标签条件");
        jdbcTemplate.update("UPDATE tb_voucher_batch_job SET status='PARTIAL_FAILED' WHERE id=?", job.getId());

        VoucherBatchJob reopened = batchJobService.retryFailures(job.getId(), null);
        assertThat(reopened.getStatus()).isEqualTo("READY");
        assertThat(itemUserStatus(job.getId(), users.get(0))).isEqualTo("PENDING");
        assertThat(itemUserStatus(job.getId(), users.get(1))).isEqualTo("SKIPPED");
        processToEnd(job.getId(), 2);
        assertThat(itemCount(job.getId(), "GRANTED")).isEqualTo(1);
        assertThat(itemCount(job.getId(), "SKIPPED")).isEqualTo(1);

        // A captured rule version is never replaced; a changed campaign is a stable skip.
        VoucherCampaign changedCampaign = createActiveCampaign(tag.getId(), "rule-change", "ADMIN", 10);
        VoucherBatchJob changedJob = batchJobService.create(changedCampaign.getId(),
                batchRequest("rule-change", changedCampaign));
        batchJobService.snapshotOne();
        jdbcTemplate.update("UPDATE tb_voucher_campaign SET rule_version=rule_version+1 WHERE id=?",
                changedCampaign.getId());
        processToEnd(changedJob.getId(), 2);
        assertThat(itemCount(changedJob.getId(), "SKIPPED")).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT last_error_code FROM tb_voucher_batch_item WHERE job_id=? LIMIT 1",
                String.class, changedJob.getId())).isEqualTo("CAMPAIGN_RULE_CHANGED");

        VoucherCampaign quotaCampaign = createActiveCampaign(tag.getId(), "quota", "ADMIN", 1);
        VoucherBatchJob quotaJob = batchJobService.create(quotaCampaign.getId(),
                batchRequest("quota", quotaCampaign));
        batchJobService.snapshotOne();
        processToEnd(quotaJob.getId(), 2);
        assertThat(itemCount(quotaJob.getId(), "GRANTED")).isEqualTo(1);
        assertThat(itemCount(quotaJob.getId(), "SKIPPED")).isEqualTo(1);
        assertThat(jdbcTemplate.queryForList(
                "SELECT last_error_code FROM tb_voucher_batch_item WHERE job_id=?", String.class, quotaJob.getId()))
                .contains("CAMPAIGN_QUOTA_EXHAUSTED");

        VoucherCampaign disabledCampaign = createActiveCampaign(tag.getId(), "disabled-tag", "ADMIN", 10);
        VoucherBatchJob disabledJob = batchJobService.create(disabledCampaign.getId(),
                batchRequest("disabled-tag", disabledCampaign));
        batchJobService.snapshotOne();
        jdbcTemplate.update("UPDATE tb_marketing_tag SET status='DISABLED' WHERE id=?", tag.getId());
        processToEnd(disabledJob.getId(), 2);
        assertThat(itemCount(disabledJob.getId(), "SKIPPED")).isEqualTo(2);
        assertThat(jdbcTemplate.queryForList(
                "SELECT last_error_code FROM tb_voucher_batch_item WHERE job_id=?", String.class, disabledJob.getId()))
                .containsOnly("CAMPAIGN_INELIGIBLE");

        // The same job cannot be read through another merchant scope.
        Long otherMerchant = createMerchant("scope");
        Long otherOperator = createAccount(otherMerchant, "scope");
        AdminPrincipalHolder.save(merchantPrincipal(otherOperator, otherMerchant));
        assertThatThrownBy(() -> batchJobService.get(job.getId(), null))
                .isInstanceOf(ApiStatusException.class)
                .satisfies(error -> assertThat(((ApiStatusException) error).getStatus().value()).isEqualTo(404));
    }

    @Test
    void grantCommittedBeforeItemUpdateConvergesToIdempotentWithoutSecondQuotaOrOutbox() {
        Long operatorId = createAccount(MERCHANT_ID, "interrupt");
        AdminPrincipalHolder.save(merchantPrincipal(operatorId, MERCHANT_ID));
        Long userId = createUsers(1, "interrupt").get(0);
        MarketingTag tag = createTag("INTERRUPT");
        addMembers(tag, Collections.singletonList(userId));
        VoucherCampaign campaign = createActiveCampaign(tag.getId(), "interrupt", "ADMIN", 2);
        VoucherBatchJob job = batchJobService.create(campaign.getId(), batchRequest("interrupt", campaign));
        batchJobService.snapshotOne();
        VoucherBatchItem item = batchItemMapper.selectPageScoped(job.getId(), MERCHANT_ID, 1, 0).get(0);

        VoucherGrantCommand command = new VoucherGrantCommand();
        command.setCampaignId(campaign.getId());
        command.setMerchantId(MERCHANT_ID);
        command.setUserId(userId);
        command.setExpectedRuleVersion(campaign.getRuleVersion());
        command.setOperatorId(operatorId);
        command.setSource(VoucherGrantCommand.BATCH_GRANT);
        assertThat(grantService.grant(command).getId()).isNotNull();
        assertThat(item.getStatus()).isEqualTo("PENDING");
        assertThat(batchJobService.processNextBatch(1).getProcessed()).isEqualTo(1);
        assertThat(itemUserStatus(job.getId(), userId)).isEqualTo("IDEMPOTENT");
        assertThat(grantCount(campaign.getId())).isEqualTo(1);
        assertThat(outboxCount(campaign.getId())).isEqualTo(1);
    }

    @Test
    void outboxWriteFailureRollsBackGrantAndCampaignCountTogether() {
        Long operatorId = createAccount(MERCHANT_ID, "rollback");
        AdminPrincipalHolder.save(merchantPrincipal(operatorId, MERCHANT_ID));
        Long userId = createUsers(1, "rollback").get(0);
        MarketingTag tag = createTag("ROLLBACK");
        addMembers(tag, Collections.singletonList(userId));
        VoucherCampaign campaign = createActiveCampaign(tag.getId(), "rollback", "ADMIN", 1);

        jdbcTemplate.execute("DROP TRIGGER IF EXISTS m6c_test_outbox_failure");
        jdbcTemplate.execute("CREATE TRIGGER m6c_test_outbox_failure " +
                "BEFORE INSERT ON tb_voucher_grant_notification_outbox FOR EACH ROW " +
                "SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'M6C_TEST_OUTBOX_FAILURE'");
        try {
            VoucherGrantCommand command = new VoucherGrantCommand();
            command.setCampaignId(campaign.getId());
            command.setMerchantId(MERCHANT_ID);
            command.setUserId(userId);
            command.setExpectedRuleVersion(campaign.getRuleVersion());
            command.setOperatorId(operatorId);
            command.setSource(VoucherGrantCommand.BATCH_GRANT);
            assertThatThrownBy(() -> grantService.grant(command))
                    .isInstanceOf(RuntimeException.class);
        } finally {
            jdbcTemplate.execute("DROP TRIGGER IF EXISTS m6c_test_outbox_failure");
        }
        assertThat(grantCount(campaign.getId())).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT granted_count FROM tb_voucher_campaign WHERE id=?", Integer.class, campaign.getId()))
                .isZero();
        assertThat(outboxCount(campaign.getId())).isZero();
    }

    private VoucherBatchJobCreateRequest batchRequest(String requestId, VoucherCampaign campaign) {
        VoucherBatchJobCreateRequest request = new VoucherBatchJobCreateRequest();
        request.setRequestId(requestId);
        request.setExpectedRuleVersion(campaign.getRuleVersion());
        return request;
    }

    private VoucherCampaign createActiveCampaign(Long tagId, String suffix, String grantMode, int quota) {
        VoucherCampaignRequest request = new VoucherCampaignRequest();
        request.setVoucherId(VOUCHER_ID);
        request.setName(FIXTURE_PREFIX + suffix + "_" + System.nanoTime());
        request.setGrantMode(grantMode);
        request.setEligibilityType("MANUAL_TAG");
        request.setRequiredTagId(tagId);
        LocalDateTime now = jdbcTemplate.queryForObject("SELECT CURRENT_TIMESTAMP", LocalDateTime.class);
        request.setBeginTime(now.minusMinutes(5));
        request.setEndTime(now.plusDays(1));
        request.setQuotaTotal(quota);
        VoucherCampaign draft = marketingAdminService.createCampaign(request);
        VoucherCampaignStatusRequest status = new VoucherCampaignStatusRequest();
        status.setExpectedStatus(draft.getStatus());
        status.setExpectedRuleVersion(draft.getRuleVersion());
        status.setStatus("ACTIVE");
        return marketingAdminService.changeStatus(draft.getId(), status);
    }

    private MarketingTag createTag(String suffix) {
        MarketingTagRequest request = new MarketingTagRequest();
        request.setCode("M6C_" + suffix + "_" + (System.nanoTime() % 100000));
        request.setName(FIXTURE_PREFIX + suffix);
        return marketingAdminService.createTag(request);
    }

    private void addMembers(MarketingTag tag, List<Long> users) {
        for (Long user : users) {
            jdbcTemplate.update("INSERT INTO tb_voucher_order(id,user_id,voucher_id,pay_type,status) VALUES(?,?,?,?,?)",
                    ORDER_ID.incrementAndGet(), user, VOUCHER_ID, 1, 2);
            marketingAdminService.addMember(tag.getId(), user, new MarketingTagMemberRequest());
        }
    }

    private List<Long> createUsers(int count, String suffix) {
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            String phone = String.format("139%08d", Math.floorMod(ORDER_ID.incrementAndGet(), 100000000L));
            jdbcTemplate.update("INSERT INTO tb_user(phone,nick_name) VALUES(?,?)",
                    phone, FIXTURE_PREFIX + suffix + "_" + i);
            ids.add(jdbcTemplate.queryForObject("SELECT id FROM tb_user WHERE phone=?", Long.class, phone));
        }
        return ids;
    }

    private Long createAccount(Long merchantId, String suffix) {
        String username = ACCOUNT_PREFIX + suffix + "." + System.nanoTime();
        jdbcTemplate.update("INSERT INTO tb_admin_account " +
                        "(merchant_id,username,password_hash,display_name,scope_type,status) VALUES(?,?,?,?,?,1)",
                merchantId, username,
                "$2a$10$012345678901234567890u012345678901234567890123456789012",
                username, AdminPrincipal.SCOPE_MERCHANT);
        return jdbcTemplate.queryForObject("SELECT id FROM tb_admin_account WHERE username=?", Long.class, username);
    }

    private Long createMerchant(String suffix) {
        String code = FIXTURE_PREFIX + suffix + "_" + System.nanoTime();
        jdbcTemplate.update("INSERT INTO tb_merchant(code,name,status) VALUES(?,?,1)", code, code);
        return jdbcTemplate.queryForObject("SELECT id FROM tb_merchant WHERE code=?", Long.class, code);
    }

    private AdminPrincipal merchantPrincipal(Long accountId, Long merchantId) {
        AdminPrincipal principal = new AdminPrincipal();
        principal.setAccountId(accountId);
        principal.setMerchantId(merchantId);
        principal.setScopeType(AdminPrincipal.SCOPE_MERCHANT);
        return principal;
    }

    private int processToEnd(Long jobId, int batchSize) {
        int batches = 0;
        for (int i = 0; i < 100; i++) {
            VoucherBatchJobService.BatchRunResult result = batchJobService.processNextBatch(batchSize);
            if (result.getProcessed() == 0) break;
            batches++;
            if (result.isDrained()) break;
        }
        assertThat(batchJobService.get(jobId, null).getStatus())
                .isIn("COMPLETED", "PARTIAL_FAILED");
        return batches;
    }

    private int itemCount(Long jobId, String status) {
        return (int) batchItemMapper.countByJobAndStatus(jobId, status);
    }

    private int grantCount(Long campaignId) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM tb_voucher_grant WHERE campaign_id=?",
                Integer.class, campaignId);
    }

    private int outboxCount(Long campaignId) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM tb_voucher_grant_notification_outbox o " +
                "JOIN tb_voucher_grant g ON g.id=o.grant_id WHERE g.campaign_id=?", Integer.class, campaignId);
    }

    private String itemUserStatus(Long jobId, Long userId) {
        List<String> statuses = jdbcTemplate.queryForList(
                "SELECT status FROM tb_voucher_batch_item WHERE job_id=? AND user_id=?", String.class,
                jobId, userId);
        return statuses.isEmpty() ? null : statuses.get(0);
    }

    private <T> List<T> concurrently(int count, Callable<T> callable) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(count);
        CountDownLatch ready = new CountDownLatch(count);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<T>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < count; i++) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    if (!start.await(30, TimeUnit.SECONDS)) throw new IllegalStateException("barrier timeout");
                    return callable.call();
                }));
            }
            assertThat(ready.await(30, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            List<T> values = new ArrayList<>();
            for (Future<T> future : futures) {
                try {
                    values.add(future.get(90, TimeUnit.SECONDS));
                } catch (ExecutionException failure) {
                    throw new AssertionError("concurrent M6C create failed", failure.getCause());
                }
            }
            return values;
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(90, TimeUnit.SECONDS)).isTrue();
        }
    }

    private void cleanupFixtures() {
        jdbcTemplate.update("DELETE i FROM tb_voucher_batch_item i JOIN tb_voucher_batch_job j ON j.id=i.job_id " +
                "JOIN tb_voucher_campaign c ON c.id=j.campaign_id WHERE c.name LIKE ?", FIXTURE_PREFIX + "%");
        jdbcTemplate.update("DELETE j FROM tb_voucher_batch_job j JOIN tb_voucher_campaign c ON c.id=j.campaign_id " +
                "WHERE c.name LIKE ?", FIXTURE_PREFIX + "%");
        jdbcTemplate.update("DELETE g FROM tb_voucher_grant g JOIN tb_voucher_campaign c ON c.id=g.campaign_id " +
                "WHERE c.name LIKE ?", FIXTURE_PREFIX + "%");
        jdbcTemplate.update("DELETE m FROM tb_marketing_tag_member m JOIN tb_marketing_tag t ON t.id=m.tag_id " +
                "WHERE t.code LIKE ?", "M6C_%");
        jdbcTemplate.update("DELETE FROM tb_voucher_campaign WHERE name LIKE ?", FIXTURE_PREFIX + "%");
        jdbcTemplate.update("DELETE FROM tb_marketing_tag WHERE code LIKE ?", "M6C_%");
        jdbcTemplate.update("DELETE o FROM tb_voucher_order o JOIN tb_user u ON u.id=o.user_id " +
                "WHERE u.nick_name LIKE ?", FIXTURE_PREFIX + "%");
        jdbcTemplate.update("DELETE FROM tb_user WHERE nick_name LIKE ?", FIXTURE_PREFIX + "%");
        jdbcTemplate.update("DELETE FROM tb_admin_account WHERE username LIKE ?", ACCOUNT_PREFIX + "%");
        jdbcTemplate.update("DELETE FROM tb_merchant WHERE code LIKE ?", FIXTURE_PREFIX + "%");
    }
}
