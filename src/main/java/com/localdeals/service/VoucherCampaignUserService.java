package com.localdeals.service;

import com.localdeals.dto.VoucherGrantClaimRequest;
import com.localdeals.dto.VoucherGrantCommand;
import com.localdeals.entity.VoucherCampaign;
import com.localdeals.entity.VoucherGrant;
import com.localdeals.mapper.VoucherCampaignMapper;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class VoucherCampaignUserService {
    private final VoucherCampaignMapper campaignMapper;
    private final VoucherGrantService grantService;

    public VoucherCampaignUserService(VoucherCampaignMapper campaignMapper,
            VoucherGrantService grantService) {
        this.campaignMapper = campaignMapper;
        this.grantService = grantService;
    }

    public List<VoucherCampaign> listForShop(Long shopId, Long userId) {
        requireId(shopId, "店铺不能为空");
        requireId(userId, "用户不能为空");
        return campaignMapper.selectForUserShop(shopId, userId);
    }

    public VoucherGrant claim(Long campaignId, VoucherGrantClaimRequest request, Long userId) {
        requireId(campaignId, "活动不能为空");
        requireId(userId, "用户不能为空");
        if (request == null || request.getExpectedRuleVersion() == null ||
                request.getExpectedRuleVersion() < 1) {
            throw new IllegalArgumentException("expected ruleVersion 不能为空");
        }
        VoucherGrantCommand command = new VoucherGrantCommand();
        command.setCampaignId(campaignId);
        command.setUserId(userId);
        command.setExpectedRuleVersion(request.getExpectedRuleVersion());
        command.setSource(VoucherGrantCommand.USER_CLAIM);
        return grantService.grant(command);
    }

    public List<VoucherGrant> listMine(Long userId) {
        requireId(userId, "用户不能为空");
        return grantService.listMine(userId);
    }

    private void requireId(Long id, String message) {
        if (id == null || id < 1) throw new IllegalArgumentException(message);
    }
}
