package com.localdeals.marketing;

import com.localdeals.dto.AdminPrincipal;
import com.localdeals.dto.MarketingTagMemberRequest;
import com.localdeals.dto.MarketingTagRequest;
import com.localdeals.dto.VoucherBatchJobCreateRequest;
import com.localdeals.dto.VoucherCampaignRequest;
import com.localdeals.dto.VoucherCampaignStatusRequest;
import com.localdeals.entity.MarketingTag;
import com.localdeals.entity.VoucherBatchJob;
import com.localdeals.entity.VoucherCampaign;
import com.localdeals.mapper.VoucherGrantNotificationOutboxMapper;
import com.localdeals.service.VoucherBatchJobService;
import com.localdeals.service.VoucherGrantNotificationOutboxService;
import com.localdeals.service.MarketingAdminService;
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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = M6cOutboxPersistenceTestConfiguration.class)
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "M6C_ISOLATED", matches = "true")
class M6cRedisRecoveryIT {
    private static final long MERCHANT_ID = 1L;
    private static final long VOUCHER_ID = 1L;
    private static final String FIXTURE_PREFIX = "M6C_REDIS_";
    private static final String ACCOUNT_PREFIX = "m6c.redis.";
    private static final AtomicLong IDS = new AtomicLong(920000000000000000L);

    @DynamicPropertySource
    static void registerM6cDatasource(DynamicPropertyRegistry registry) {
        M6cDatasourceGuard.register(registry);
        M6cRedisGuard.assertSentinel();
    }

    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private MarketingAdminService marketingAdminService;
    @Autowired private VoucherBatchJobService batchJobService;
    @Autowired private VoucherGrantNotificationOutboxService outboxService;
    @Autowired private VoucherGrantNotificationOutboxMapper outboxMapper;

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
    void stoppedRedisDoesNotUndoJobAndRecoveryPublishesTheDurablePendingRow() throws Exception {
        Long accountId = createAccount();
        AdminPrincipalHolder.save(principal(accountId));
        Long userId = createUser();
        MarketingTag tag = createTag();
        jdbcTemplate.update("INSERT INTO tb_voucher_order(id,user_id,voucher_id,pay_type,status) VALUES(?,?,?,?,?)",
                IDS.incrementAndGet(), userId, VOUCHER_ID, 1, 2);
        marketingAdminService.addMember(tag.getId(), userId, new MarketingTagMemberRequest());
        VoucherCampaign campaign = createCampaign(tag.getId(), accountId);
        VoucherBatchJob job = batchJobService.create(campaign.getId(), batchRequest(campaign));
        assertThat(batchJobService.snapshotOne()).isEqualTo(1);
        assertThat(outboxMapper.countPending()).isZero();

        String redisContainer = "m6c-" + required("M6C_RUN_ID") + "-redis";
        assertOwnedRedisContainer(redisContainer);
        boolean stopped = false;
        try {
            runDocker("stop", redisContainer);
            stopped = true;

            // The job has no Redis dependency: its grant transaction completes while Redis is down.
            assertThat(batchJobService.processNextBatch(10).getProcessed()).isEqualTo(1);
            assertThat(batchJobService.get(job.getId(), null).getStatus()).isEqualTo("COMPLETED");
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT granted_count FROM tb_voucher_campaign WHERE id=?", Integer.class, campaign.getId()))
                    .isEqualTo(1);

            VoucherGrantNotificationOutboxService.FlushResult failed = outboxService.processNextBatch(10);
            assertThat(failed.getPublished()).isZero();
            assertThat(failed.getRetried()).isEqualTo(1);
            assertThat(outboxMapper.countPending()).isEqualTo(1);
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT attempts FROM tb_voucher_grant_notification_outbox WHERE status='PENDING'", Integer.class))
                    .isEqualTo(1);
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM tb_voucher_grant WHERE campaign_id=?", Integer.class, campaign.getId()))
                    .isEqualTo(1);
            Long oldestAgeMillis = jdbcTemplate.queryForObject(
                    "SELECT TIMESTAMPDIFF(MICROSECOND, MIN(create_time), CURRENT_TIMESTAMP) DIV 1000 " +
                            "FROM tb_voucher_grant_notification_outbox WHERE status='PENDING'",
                    Long.class);
            assertThat(oldestAgeMillis).isNotNull();
            System.out.println("M6C_OUTBOX_BACKLOG pending=1 oldest_age_ms=" + oldestAgeMillis +
                    " source=create_time");
        } finally {
            if (stopped) {
                runDocker("start", redisContainer);
                waitForRedis(redisContainer);
            }
        }

        // Redis is intentionally ephemeral in this isolated stack. Re-establish the run marker
        // on the exact owned container after restart, then verify it through the configured port.
        runDocker("exec", redisContainer, "redis-cli", "SET",
                "m6c:sentinel:" + required("M6C_RUN_ID"), required("M6C_RUN_ID"));
        M6cRedisGuard.assertSentinel();
        jdbcTemplate.update("UPDATE tb_voucher_grant_notification_outbox SET next_attempt_time=CURRENT_TIMESTAMP " +
                "WHERE status='PENDING'");
        long recoveryStarted = System.nanoTime();
        VoucherGrantNotificationOutboxService.FlushResult recovered = null;
        for (int attempt = 0; attempt < 10 && outboxMapper.countPending() > 0; attempt++) {
            recovered = outboxService.processNextBatch(10);
            if (outboxMapper.countPending() > 0) Thread.sleep(250L);
        }
        long recoveryMillis = (System.nanoTime() - recoveryStarted) / 1_000_000L;
        assertThat(recovered).isNotNull();
        assertThat(recovered.getPublished()).isEqualTo(1);
        assertThat(outboxMapper.countPending()).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM tb_voucher_grant_notification_outbox WHERE grant_id=" +
                        "(SELECT id FROM tb_voucher_grant WHERE campaign_id=?)", String.class, campaign.getId()))
                .isEqualTo("PUBLISHED");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT published_at IS NOT NULL FROM tb_voucher_grant_notification_outbox WHERE status='PUBLISHED'",
                Boolean.class)).isTrue();
        assertThat(recoveryMillis).isGreaterThan(0L);
        System.out.println("M6C_OUTBOX_METRIC target=1 granted=1 idempotent=0 skipped=0 failed=0 " +
                "pending_before_recovery=1 oldest_age_source=create_time recovery_ms=" + recoveryMillis +
                " redis_recovery=published");
    }

    private VoucherCampaign createCampaign(Long tagId, Long accountId) {
        VoucherCampaignRequest request = new VoucherCampaignRequest();
        request.setVoucherId(VOUCHER_ID);
        request.setName(FIXTURE_PREFIX + "CAMPAIGN");
        request.setGrantMode("ADMIN");
        request.setEligibilityType("MANUAL_TAG");
        request.setRequiredTagId(tagId);
        LocalDateTime now = jdbcTemplate.queryForObject("SELECT CURRENT_TIMESTAMP", LocalDateTime.class);
        request.setBeginTime(now.minusMinutes(5));
        request.setEndTime(now.plusDays(1));
        request.setQuotaTotal(1);
        VoucherCampaign draft = marketingAdminService.createCampaign(request);
        VoucherCampaignStatusRequest status = new VoucherCampaignStatusRequest();
        status.setExpectedStatus(draft.getStatus());
        status.setExpectedRuleVersion(draft.getRuleVersion());
        status.setStatus("ACTIVE");
        return marketingAdminService.changeStatus(draft.getId(), status);
    }

    private MarketingTag createTag() {
        MarketingTagRequest request = new MarketingTagRequest();
        request.setCode("M6C_REDIS_TAG");
        request.setName(FIXTURE_PREFIX + "TAG");
        return marketingAdminService.createTag(request);
    }

    private VoucherBatchJobCreateRequest batchRequest(VoucherCampaign campaign) {
        VoucherBatchJobCreateRequest request = new VoucherBatchJobCreateRequest();
        request.setRequestId("redis-recovery");
        request.setExpectedRuleVersion(campaign.getRuleVersion());
        return request;
    }

    private Long createUser() {
        String phone = String.format("138%08d", Math.floorMod(IDS.incrementAndGet(), 100000000L));
        jdbcTemplate.update("INSERT INTO tb_user(phone,nick_name) VALUES(?,?)", phone, FIXTURE_PREFIX + "USER");
        return jdbcTemplate.queryForObject("SELECT id FROM tb_user WHERE phone=?", Long.class, phone);
    }

    private Long createAccount() {
        String username = ACCOUNT_PREFIX + System.nanoTime();
        jdbcTemplate.update("INSERT INTO tb_admin_account " +
                        "(merchant_id,username,password_hash,display_name,scope_type,status) VALUES(?,?,?,?,?,1)",
                MERCHANT_ID, username,
                "$2a$10$012345678901234567890u012345678901234567890123456789012",
                username, AdminPrincipal.SCOPE_MERCHANT);
        return jdbcTemplate.queryForObject("SELECT id FROM tb_admin_account WHERE username=?", Long.class, username);
    }

    private AdminPrincipal principal(Long accountId) {
        AdminPrincipal principal = new AdminPrincipal();
        principal.setAccountId(accountId);
        principal.setMerchantId(MERCHANT_ID);
        principal.setScopeType(AdminPrincipal.SCOPE_MERCHANT);
        return principal;
    }

    private void assertOwnedRedisContainer(String container) throws Exception {
        assertThat(runDocker("inspect", "--format",
                "{{index .Config.Labels \"com.localdeals.m6c.run-id\"}}", container))
                .isEqualTo(required("M6C_RUN_ID"));
        assertThat(runDocker("inspect", "--format", "{{index .Config.Labels \"com.localdeals.m6c.role\"}}",
                container)).isEqualTo("redis");
    }

    private void waitForRedis(String container) throws Exception {
        for (int i = 0; i < 40; i++) {
            if (runDockerAllowFailure("exec", container, "redis-cli", "PING")) return;
            Thread.sleep(250L);
        }
        throw new IllegalStateException("M6C Redis did not recover");
    }

    private String runDocker(String... args) throws Exception {
        ProcessResult result = runProcess(args);
        if (result.exitCode != 0) throw new IllegalStateException("M6C docker command failed: " + result.output);
        return result.output.trim();
    }

    private boolean runDockerAllowFailure(String... args) throws Exception {
        return runProcess(args).exitCode == 0;
    }

    private ProcessResult runProcess(String... args) throws Exception {
        String[] command = new String[args.length + 1];
        command[0] = "docker";
        System.arraycopy(args, 0, command, 1, args.length);
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = read(process.getInputStream());
        return new ProcessResult(process.waitFor(), output);
    }

    private String read(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[1024];
        int count;
        while ((count = input.read(buffer)) >= 0) output.write(buffer, 0, count);
        return new String(output.toByteArray(), StandardCharsets.UTF_8);
    }

    private void cleanupFixtures() {
        jdbcTemplate.update("DELETE i FROM tb_voucher_batch_item i JOIN tb_voucher_batch_job j ON j.id=i.job_id " +
                "JOIN tb_voucher_campaign c ON c.id=j.campaign_id WHERE c.name LIKE ?", FIXTURE_PREFIX + "%");
        jdbcTemplate.update("DELETE j FROM tb_voucher_batch_job j JOIN tb_voucher_campaign c ON c.id=j.campaign_id " +
                "WHERE c.name LIKE ?", FIXTURE_PREFIX + "%");
        jdbcTemplate.update("DELETE g FROM tb_voucher_grant g JOIN tb_voucher_campaign c ON c.id=g.campaign_id " +
                "WHERE c.name LIKE ?", FIXTURE_PREFIX + "%");
        jdbcTemplate.update("DELETE m FROM tb_marketing_tag_member m JOIN tb_marketing_tag t ON t.id=m.tag_id " +
                "WHERE t.code LIKE 'M6C_REDIS_%'");
        jdbcTemplate.update("DELETE FROM tb_voucher_campaign WHERE name LIKE ?", FIXTURE_PREFIX + "%");
        jdbcTemplate.update("DELETE FROM tb_marketing_tag WHERE code LIKE 'M6C_REDIS_%'");
        jdbcTemplate.update("DELETE o FROM tb_voucher_order o JOIN tb_user u ON u.id=o.user_id " +
                "WHERE u.nick_name LIKE ?", FIXTURE_PREFIX + "%");
        jdbcTemplate.update("DELETE FROM tb_user WHERE nick_name LIKE ?", FIXTURE_PREFIX + "%");
        jdbcTemplate.update("DELETE FROM tb_admin_account WHERE username LIKE ?", ACCOUNT_PREFIX + "%");
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.trim().isEmpty()) throw new IllegalStateException(name + " is required");
        return value.trim();
    }

    private static final class ProcessResult {
        private final int exitCode;
        private final String output;

        private ProcessResult(int exitCode, String output) {
            this.exitCode = exitCode;
            this.output = output;
        }
    }
}
