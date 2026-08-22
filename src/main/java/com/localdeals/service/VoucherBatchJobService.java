package com.localdeals.service;

import com.localdeals.dto.AdminPrincipal;
import com.localdeals.dto.VoucherBatchJobCreateRequest;
import com.localdeals.entity.MarketingTag;
import com.localdeals.entity.VoucherBatchItem;
import com.localdeals.entity.VoucherBatchJob;
import com.localdeals.entity.VoucherCampaign;
import com.localdeals.exception.ApiStatusException;
import com.localdeals.mapper.MarketingTagMapper;
import com.localdeals.mapper.VoucherBatchItemMapper;
import com.localdeals.mapper.VoucherBatchJobMapper;
import com.localdeals.mapper.VoucherCampaignMapper;
import com.localdeals.utils.AdminPrincipalHolder;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Application service for the bounded, manual-tag M6C job contract. */
@Service
public class VoucherBatchJobService {
    static final String SNAPSHOTTING = "SNAPSHOTTING";
    static final String READY = "READY";
    static final String RUNNING = "RUNNING";
    static final String PAUSED = "PAUSED";
    static final String COMPLETED = "COMPLETED";
    static final String PARTIAL_FAILED = "PARTIAL_FAILED";

    static final String PENDING = "PENDING";
    static final String GRANTED = "GRANTED";
    static final String IDEMPOTENT = "IDEMPOTENT";
    static final String SKIPPED = "SKIPPED";
    static final String FAILED = "FAILED";

    private static final Set<String> ALLOWED_ITEM_STATUSES = new HashSet<>(Arrays.asList(
            PENDING, GRANTED, IDEMPOTENT, SKIPPED, FAILED));
    private static final Set<String> STABLE_CODES = new HashSet<>(Arrays.asList(
            "CAMPAIGN_RULE_CHANGED", "CAMPAIGN_GRANT_MODE_UNSUPPORTED", "CAMPAIGN_NOT_ACTIVE",
            "CAMPAIGN_NOT_STARTED", "CAMPAIGN_ENDED", "CAMPAIGN_INELIGIBLE", "CAMPAIGN_QUOTA_EXHAUSTED",
            "CAMPAIGN_NOT_FOUND", "CAMPAIGN_REJECTED"));

    private final VoucherBatchJobMapper jobMapper;
    private final VoucherBatchItemMapper itemMapper;
    private final VoucherCampaignMapper campaignMapper;
    private final MarketingTagMapper tagMapper;
    private final MarketingAdminService marketingAdminService;
    private final VoucherGrantService grantService;

    public VoucherBatchJobService(VoucherBatchJobMapper jobMapper, VoucherBatchItemMapper itemMapper,
            VoucherCampaignMapper campaignMapper, MarketingTagMapper tagMapper,
            MarketingAdminService marketingAdminService, VoucherGrantService grantService) {
        this.jobMapper = jobMapper;
        this.itemMapper = itemMapper;
        this.campaignMapper = campaignMapper;
        this.tagMapper = tagMapper;
        this.marketingAdminService = marketingAdminService;
        this.grantService = grantService;
    }

    @Transactional
    public VoucherBatchJob create(Long campaignId, VoucherBatchJobCreateRequest request) {
        if (campaignId == null || request == null) {
            throw new IllegalArgumentException("活动和批量 Job 请求不能为空");
        }
        Long merchantId = marketingAdminService.resolveMerchant(request.getMerchantId());
        String requestId = normalizeRequestId(request.getRequestId());
        VoucherBatchJob existing = jobMapper.selectByMerchantAndRequest(merchantId, requestId);
        if (existing != null) {
            return getScoped(existing.getId(), merchantId);
        }
        if (request.getExpectedRuleVersion() == null || request.getExpectedRuleVersion() < 1) {
            throw new IllegalArgumentException("expected ruleVersion 不能为空");
        }

        VoucherCampaign campaign = campaignMapper.selectScopedForUpdate(campaignId, merchantId);
        if (campaign == null) throw notFound();
        validateCampaign(campaign, request.getExpectedRuleVersion());
        MarketingTag tag = tagMapper.selectScoped(campaign.getRequiredTagId(), merchantId);
        if (tag == null || !"ACTIVE".equals(tag.getStatus())) {
            throw new ApiStatusException(HttpStatus.CONFLICT, "CAMPAIGN_TAG_INVALID", "活动标签不存在或已停用");
        }
        AdminPrincipal principal = currentPrincipal();

        VoucherBatchJob job = new VoucherBatchJob();
        job.setMerchantId(merchantId);
        job.setCampaignId(campaignId);
        job.setOperatorId(principal.getAccountId());
        job.setRequestId(requestId);
        job.setCapturedRuleVersion(campaign.getRuleVersion());
        job.setTargetTagId(campaign.getRequiredTagId());
        jobMapper.insertIdempotent(job);
        // A concurrent insert may win the unique request boundary. Always re-read the durable row.
        VoucherBatchJob durable = jobMapper.selectByMerchantAndRequestForUpdate(merchantId, requestId);
        if (durable == null) throw new IllegalStateException("批量 Job 创建后无法读取");
        VoucherBatchJob result = jobMapper.selectScopedWithCountsForUpdate(durable.getId(), merchantId);
        if (result == null) throw notFound();
        return result;
    }

    public List<VoucherBatchJob> list(Long requestedMerchantId, int page, int size) {
        Long merchantId = marketingAdminService.resolveMerchant(requestedMerchantId);
        Page pageSpec = pageSpec(page, size);
        return jobMapper.selectPageScoped(merchantId, pageSpec.limit, pageSpec.offset);
    }

    public long count(Long requestedMerchantId) {
        return jobMapper.countScoped(marketingAdminService.resolveMerchant(requestedMerchantId));
    }

    public VoucherBatchJob get(Long jobId, Long requestedMerchantId) {
        return getScoped(jobId, marketingAdminService.resolveMerchant(requestedMerchantId));
    }

    public List<VoucherBatchItem> listItems(Long jobId, Long requestedMerchantId, String requestedStatus,
            int page, int size) {
        Long merchantId = marketingAdminService.resolveMerchant(requestedMerchantId);
        getScoped(jobId, merchantId);
        Page pageSpec = pageSpec(page, size);
        String status = normalizeStatus(requestedStatus);
        if (status == null) {
            return itemMapper.selectPageScoped(jobId, merchantId, pageSpec.limit, pageSpec.offset);
        }
        return itemMapper.selectPageScopedByStatus(jobId, merchantId, status,
                pageSpec.limit, pageSpec.offset);
    }

    public long countItems(Long jobId, Long requestedMerchantId, String requestedStatus) {
        Long merchantId = marketingAdminService.resolveMerchant(requestedMerchantId);
        getScoped(jobId, merchantId);
        String status = normalizeStatus(requestedStatus);
        return status == null ? itemMapper.countScoped(jobId, merchantId)
                : itemMapper.countScopedByStatus(jobId, merchantId, status);
    }

    public VoucherBatchJob pause(Long jobId, Long requestedMerchantId) {
        Long merchantId = marketingAdminService.resolveMerchant(requestedMerchantId);
        VoucherBatchJob job = getScoped(jobId, merchantId);
        if (PAUSED.equals(job.getStatus())) return job;
        if (jobMapper.pauseScoped(jobId, merchantId) != 1) {
            throw new ApiStatusException(HttpStatus.CONFLICT, "BATCH_JOB_NOT_PAUSABLE", "Job 当前不能暂停");
        }
        return getScoped(jobId, merchantId);
    }

    public VoucherBatchJob resume(Long jobId, Long requestedMerchantId) {
        Long merchantId = marketingAdminService.resolveMerchant(requestedMerchantId);
        VoucherBatchJob job = getScoped(jobId, merchantId);
        if (READY.equals(job.getStatus()) || RUNNING.equals(job.getStatus())) return job;
        if (jobMapper.resumeScoped(jobId, merchantId) != 1) {
            throw new ApiStatusException(HttpStatus.CONFLICT, "BATCH_JOB_NOT_RESUMABLE", "Job 当前不能恢复");
        }
        return getScoped(jobId, merchantId);
    }

    @Transactional
    public VoucherBatchJob retryFailures(Long jobId, Long requestedMerchantId) {
        Long merchantId = marketingAdminService.resolveMerchant(requestedMerchantId);
        VoucherBatchJob job = jobMapper.selectScopedForUpdate(jobId, merchantId);
        if (job == null) throw notFound();
        int reset = itemMapper.resetFailed(jobId);
        if (reset == 0) {
            throw new ApiStatusException(HttpStatus.CONFLICT, "BATCH_JOB_NO_RETRYABLE_FAILURE",
                    "Job 没有可重试的技术失败项");
        }
        if (PARTIAL_FAILED.equals(job.getStatus())) {
            jobMapper.reopenAfterRetry(jobId, merchantId);
        }
        return getScoped(jobId, merchantId);
    }

    @Transactional
    public int snapshotOne() {
        VoucherBatchJob job = jobMapper.selectNextSnapshotForUpdate();
        if (job == null) return 0;
        itemMapper.snapshotActiveMembers(job.getId(), job.getMerchantId(), job.getTargetTagId());
        int count = itemMapper.countByJob(job.getId());
        if (jobMapper.markSnapshotReady(job.getId(), count) != 1) {
            throw new IllegalStateException("批量 Job 快照状态更新失败");
        }
        return count;
    }

    @Transactional
    public BatchRunResult processNextBatch(int batchSize) {
        if (batchSize < 1) throw new IllegalArgumentException("batchSize 必须为正数");
        VoucherBatchJob job = jobMapper.selectNextRunnableForUpdate();
        if (job == null) return BatchRunResult.empty();
        if (READY.equals(job.getStatus()) && jobMapper.markRunning(job.getId()) != 1) {
            throw new IllegalStateException("批量 Job 无法进入 RUNNING");
        }
        List<VoucherBatchItem> items = itemMapper.selectPendingForUpdate(job.getId(), batchSize);
        if (items.isEmpty()) {
            finalizeIfDrained(job.getId());
            return new BatchRunResult(job.getId(), 0, true);
        }

        for (VoucherBatchItem item : items) {
            VoucherGrantService.GrantAttempt attempt;
            try {
                com.localdeals.dto.VoucherGrantCommand command = new com.localdeals.dto.VoucherGrantCommand();
                command.setCampaignId(job.getCampaignId());
                command.setMerchantId(job.getMerchantId());
                command.setUserId(item.getUserId());
                command.setExpectedRuleVersion(job.getCapturedRuleVersion());
                command.setOperatorId(job.getOperatorId());
                command.setSource(com.localdeals.dto.VoucherGrantCommand.BATCH_GRANT);
                attempt = grantService.grantInternal(command);
                String status = attempt.getOutcome() == VoucherGrantService.GrantAttempt.Outcome.GRANTED
                        ? GRANTED : IDEMPOTENT;
                itemMapper.markOutcome(item.getId(), status,
                        attempt.getGrant() == null ? null : attempt.getGrant().getId(), null, null);
            } catch (RuntimeException failure) {
                FailureDecision decision = classify(failure);
                itemMapper.markOutcome(item.getId(), decision.status, null,
                        decision.code, decision.message);
            }
        }
        boolean drained = itemMapper.countByJobAndStatus(job.getId(), PENDING) == 0;
        if (drained) finalizeIfDrained(job.getId());
        return new BatchRunResult(job.getId(), items.size(), drained);
    }

    private void finalizeIfDrained(Long jobId) {
        long failed = itemMapper.countByJobAndStatus(jobId, FAILED);
        long pending = itemMapper.countByJobAndStatus(jobId, PENDING);
        if (pending != 0) return;
        if (failed == 0) {
            jobMapper.markCompleted(jobId);
        } else {
            jobMapper.markPartialFailed(jobId);
        }
    }

    private FailureDecision classify(RuntimeException failure) {
        if (failure instanceof ApiStatusException) {
            ApiStatusException api = (ApiStatusException) failure;
            String code = api.getCode();
            if (code != null && STABLE_CODES.contains(code)) {
                return new FailureDecision(SKIPPED, code, stableMessage(code));
            }
            if (api.getStatus() != null && api.getStatus().is4xxClientError()) {
                return new FailureDecision(SKIPPED,
                        code == null ? "CAMPAIGN_REJECTED" : "CAMPAIGN_REJECTED",
                        "当前活动条件不满足，未发放");
            }
        }
        return new FailureDecision(FAILED, "TECHNICAL_ERROR", "技术错误，等待 retry-failures");
    }

    private String stableMessage(String code) {
        if ("CAMPAIGN_INELIGIBLE".equals(code)) return "当前不满足活动标签条件";
        if ("CAMPAIGN_QUOTA_EXHAUSTED".equals(code)) return "活动额度已用尽";
        if ("CAMPAIGN_RULE_CHANGED".equals(code)) return "活动规则已变化，请创建新 Job";
        if ("CAMPAIGN_NOT_ACTIVE".equals(code)) return "活动当前未启用";
        if ("CAMPAIGN_NOT_STARTED".equals(code)) return "活动尚未开始";
        if ("CAMPAIGN_ENDED".equals(code)) return "活动已结束";
        if ("CAMPAIGN_GRANT_MODE_UNSUPPORTED".equals(code)) return "活动不支持批量发放";
        if ("CAMPAIGN_NOT_FOUND".equals(code)) return "活动已不存在";
        return "当前活动条件不满足，未发放";
    }

    private VoucherBatchJob getScoped(Long jobId, Long merchantId) {
        if (jobId == null) throw new IllegalArgumentException("Job 不能为空");
        VoucherBatchJob job = jobMapper.selectScoped(jobId, merchantId);
        if (job == null) throw notFound();
        return job;
    }

    private void validateCampaign(VoucherCampaign campaign, Long expectedRuleVersion) {
        if (!expectedRuleVersion.equals(campaign.getRuleVersion())) {
            throw new ApiStatusException(HttpStatus.CONFLICT, "CAMPAIGN_RULE_CHANGED", "活动规则版本已变化");
        }
        if (!"ADMIN".equals(campaign.getGrantMode()) && !"BOTH".equals(campaign.getGrantMode())) {
            throw new ApiStatusException(HttpStatus.CONFLICT, "CAMPAIGN_GRANT_MODE_UNSUPPORTED",
                    "活动不支持批量管理员发放");
        }
        if (!"MANUAL_TAG".equals(campaign.getEligibilityType()) || campaign.getRequiredTagId() == null) {
            throw new ApiStatusException(HttpStatus.CONFLICT, "CAMPAIGN_ELIGIBILITY_UNSUPPORTED",
                    "批量发放只支持 MANUAL_TAG 活动");
        }
        if (!"ACTIVE".equals(campaign.getStatus())) {
            throw new ApiStatusException(HttpStatus.CONFLICT, "CAMPAIGN_NOT_ACTIVE", "活动未处于 ACTIVE 状态");
        }
    }

    private String normalizeRequestId(String requestId) {
        if (!StringUtils.hasText(requestId)) throw new IllegalArgumentException("requestId 不能为空");
        String value = requestId.trim();
        if (value.length() > 96 || !value.matches("[A-Za-z0-9._:-]{1,96}")) {
            throw new IllegalArgumentException("requestId 只支持 1-96 位安全字符");
        }
        return value;
    }

    private String normalizeStatus(String value) {
        if (!StringUtils.hasText(value)) return null;
        String status = value.trim().toUpperCase(Locale.ROOT);
        if (!ALLOWED_ITEM_STATUSES.contains(status)) throw new IllegalArgumentException("item status 不合法");
        return status;
    }

    private Page pageSpec(int page, int size) {
        if (page < 1 || size < 1 || size > 100) throw new IllegalArgumentException("分页参数不合法");
        long offset = (long) (page - 1) * size;
        if (offset > Integer.MAX_VALUE) throw new IllegalArgumentException("分页范围过大");
        return new Page(size, (int) offset);
    }

    private AdminPrincipal currentPrincipal() {
        AdminPrincipal principal = AdminPrincipalHolder.get();
        if (principal == null) throw new ApiStatusException(HttpStatus.UNAUTHORIZED, "后台登录已失效");
        return principal;
    }

    private ApiStatusException notFound() {
        return new ApiStatusException(HttpStatus.NOT_FOUND, "资源不存在");
    }

    static final class Page {
        final int limit;
        final int offset;

        Page(int limit, int offset) {
            this.limit = limit;
            this.offset = offset;
        }
    }

    static final class FailureDecision {
        final String status;
        final String code;
        final String message;

        FailureDecision(String status, String code, String message) {
            this.status = status;
            this.code = code;
            this.message = message;
        }
    }

    public static final class BatchRunResult {
        private final Long jobId;
        private final int processed;
        private final boolean drained;

        BatchRunResult(Long jobId, int processed, boolean drained) {
            this.jobId = jobId;
            this.processed = processed;
            this.drained = drained;
        }

        static BatchRunResult empty() {
            return new BatchRunResult(null, 0, false);
        }

        public Long getJobId() {
            return jobId;
        }

        public int getProcessed() {
            return processed;
        }

        public boolean isDrained() {
            return drained;
        }
    }
}
