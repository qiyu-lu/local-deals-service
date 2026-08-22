package com.localdeals.service;

import com.localdeals.dto.VoucherGrantCommand;
import com.localdeals.entity.VoucherGrant;
import com.localdeals.mapper.VoucherGrantMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class VoucherGrantService {
    private final VoucherGrantMapper grantMapper;
    private final VoucherGrantTransactionService transactionService;

    public VoucherGrantService(VoucherGrantMapper grantMapper,
            VoucherGrantTransactionService transactionService) {
        this.grantMapper = grantMapper;
        this.transactionService = transactionService;
    }

    /**
     * The facade is deliberately non-transactional.  A duplicate-key race
     * must escape the transaction bean so its counter update is rolled back
     * before the idempotent re-read.
     */
    public VoucherGrant grant(VoucherGrantCommand command) {
        validateCommand(command);
        VoucherGrant existing = findExisting(command);
        if (existing != null) return existing;
        try {
            return transactionService.grant(command);
        } catch (DuplicateKeyException duplicate) {
            VoucherGrant raced = findExisting(command);
            if (raced != null) return raced;
            throw duplicate;
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
}
