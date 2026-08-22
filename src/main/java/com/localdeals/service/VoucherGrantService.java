package com.localdeals.service;

import com.localdeals.dto.VoucherGrantCommand;
import com.localdeals.entity.VoucherGrant;
import com.localdeals.exception.ApiErrorCodes;
import com.localdeals.exception.ApiStatusException;
import com.localdeals.observability.LocalDealsMetrics;
import com.localdeals.mapper.VoucherGrantMapper;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class VoucherGrantService {
    private final VoucherGrantMapper grantMapper;
    private final VoucherGrantTransactionService transactionService;
    private final ObjectProvider<LocalDealsMetrics> metricsProvider;

    public VoucherGrantService(VoucherGrantMapper grantMapper,
            VoucherGrantTransactionService transactionService,
            ObjectProvider<LocalDealsMetrics> metricsProvider) {
        this.grantMapper = grantMapper;
        this.transactionService = transactionService;
        this.metricsProvider = metricsProvider;
    }

    /**
     * The facade is deliberately non-transactional.  A duplicate-key race
     * must escape the transaction bean so its counter update is rolled back
     * before the idempotent re-read.
     */
    public VoucherGrant grant(VoucherGrantCommand command) {
        long started = System.nanoTime();
        LocalDealsMetrics.GrantResult result = LocalDealsMetrics.GrantResult.FAILURE;
        try {
            validateCommand(command);
            VoucherGrant existing = findExisting(command);
            if (existing != null) {
                result = LocalDealsMetrics.GrantResult.IDEMPOTENT;
                return existing;
            }
            try {
                VoucherGrant granted = transactionService.grant(command);
                result = LocalDealsMetrics.GrantResult.GRANTED;
                return granted;
            } catch (DuplicateKeyException duplicate) {
                VoucherGrant raced = findExisting(command);
                if (raced != null) {
                    result = LocalDealsMetrics.GrantResult.IDEMPOTENT;
                    return raced;
                }
                throw duplicate;
            }
        } catch (ApiStatusException known) {
            result = metricResult(known);
            throw known;
        } catch (DataAccessException unavailable) {
            result = LocalDealsMetrics.GrantResult.UNAVAILABLE;
            ApiStatusException apiError = new ApiStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    ApiErrorCodes.DATABASE_UNAVAILABLE, "数据暂不可用，请稍后重试");
            apiError.initCause(unavailable);
            throw apiError;
        } catch (RuntimeException failure) {
            result = LocalDealsMetrics.GrantResult.FAILURE;
            throw failure;
        } finally {
            LocalDealsMetrics metrics = metricsProvider.getIfAvailable();
            if (metrics != null) {
                metrics.recordGrant(command, result);
                metrics.recordGrantDuration(System.nanoTime() - started);
            }
        }
    }

    public List<VoucherGrant> listMine(Long userId) {
        if (userId == null) throw new IllegalArgumentException("用户不能为空");
        return grantMapper.selectMine(userId);
    }

    public List<VoucherGrant> listCampaignGrants(Long campaignId, Long merchantId) {
        if (campaignId == null || merchantId == null) {
            throw new IllegalArgumentException("活动和商户不能为空");
        }
        return grantMapper.selectCampaignGrantsScoped(campaignId, merchantId);
    }

    private VoucherGrant findExisting(VoucherGrantCommand command) {
        if (VoucherGrantCommand.ADMIN_GRANT.equals(command.getSource())) {
            return grantMapper.selectByCampaignAndMerchantAndUser(command.getCampaignId(),
                    command.getMerchantId(), command.getUserId());
        }
        return grantMapper.selectByCampaignAndUser(command.getCampaignId(), command.getUserId());
    }

    private void validateCommand(VoucherGrantCommand command) {
        if (command == null || command.getCampaignId() == null || command.getUserId() == null ||
                command.getExpectedRuleVersion() == null || command.getExpectedRuleVersion() < 1) {
            throw new IllegalArgumentException("活动、用户和 expected ruleVersion 不能为空");
        }
        String source = command.getSource();
        if (!VoucherGrantCommand.USER_CLAIM.equals(source) &&
                !VoucherGrantCommand.ADMIN_GRANT.equals(source)) {
            throw new IllegalArgumentException("发放来源不合法");
        }
        if (VoucherGrantCommand.ADMIN_GRANT.equals(source) &&
                (command.getMerchantId() == null || command.getOperatorId() == null)) {
            throw new IllegalArgumentException("管理员发放必须指定商户和操作人");
        }
        if (VoucherGrantCommand.USER_CLAIM.equals(source) && command.getOperatorId() != null) {
            throw new IllegalArgumentException("用户领取不能指定管理员操作人");
        }
    }

    private LocalDealsMetrics.GrantResult metricResult(ApiStatusException exception) {
        if (exception.getStatus() == HttpStatus.SERVICE_UNAVAILABLE) {
            return LocalDealsMetrics.GrantResult.UNAVAILABLE;
        }
        if ("CAMPAIGN_INELIGIBLE".equals(exception.getCode())) {
            return LocalDealsMetrics.GrantResult.INELIGIBLE;
        }
        if ("CAMPAIGN_QUOTA_EXHAUSTED".equals(exception.getCode())) {
            return LocalDealsMetrics.GrantResult.QUOTA_EXHAUSTED;
        }
        if ("CAMPAIGN_RULE_CHANGED".equals(exception.getCode())) {
            return LocalDealsMetrics.GrantResult.RULE_CHANGED;
        }
        if ("CAMPAIGN_NOT_ACTIVE".equals(exception.getCode()) ||
                "CAMPAIGN_NOT_STARTED".equals(exception.getCode()) ||
                "CAMPAIGN_ENDED".equals(exception.getCode())) {
            return LocalDealsMetrics.GrantResult.INACTIVE;
        }
        return LocalDealsMetrics.GrantResult.FAILURE;
    }
}
