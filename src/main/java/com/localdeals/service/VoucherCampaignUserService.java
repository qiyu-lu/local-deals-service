package com.localdeals.service;

import com.localdeals.dto.VoucherGrantClaimRequest;
import com.localdeals.dto.VoucherGrantCommand;
import com.localdeals.dto.VoucherGrantUserView;
import com.localdeals.dto.VoucherCampaignUserView;
import com.localdeals.entity.VoucherGrant;
import com.localdeals.mapper.VoucherCampaignMapper;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
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

    public List<VoucherCampaignUserView> listForShop(Long shopId, Long userId) {
        requireId(shopId, "店铺不能为空");
        requireId(userId, "用户不能为空");
        List<VoucherCampaignUserView> views = campaignMapper.selectForUserShop(shopId, userId);
        for (VoucherCampaignUserView view : views) {
            view.setClaimReason(claimReason(view.getClaimState()));
        }
        return views;
    }

    public VoucherGrantUserView claim(Long campaignId, VoucherGrantClaimRequest request, Long userId) {
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
        return toUserView(grantService.grant(command));
    }

    public List<VoucherGrantUserView> listMine(Long userId) {
        requireId(userId, "用户不能为空");
        List<VoucherGrantUserView> views = new ArrayList<>();
        for (VoucherGrant grant : grantService.listMine(userId)) {
            views.add(toUserView(grant));
        }
        return views;
    }

    private void requireId(Long id, String message) {
        if (id == null || id < 1) throw new IllegalArgumentException(message);
    }

    private String claimReason(String claimState) {
        if ("ALREADY_GRANTED".equals(claimState)) return "已领取";
        if ("NOT_STARTED".equals(claimState)) return "活动尚未开始";
        if ("ENDED".equals(claimState)) return "活动已结束";
        if ("QUOTA_EXHAUSTED".equals(claimState)) return "活动额度已用尽";
        if ("INELIGIBLE".equals(claimState)) return "暂不满足活动标签条件";
        return null;
    }

    private VoucherGrantUserView toUserView(VoucherGrant grant) {
        VoucherGrantUserView view = new VoucherGrantUserView();
        view.setId(grant.getId());
        view.setCampaignId(grant.getCampaignId());
        view.setVoucherId(grant.getVoucherId());
        view.setCampaignName(grant.getCampaignName());
        view.setVoucherTitle(grant.getVoucherTitle());
        view.setPayValue(grant.getPayValue());
        view.setActualValue(grant.getActualValue());
        view.setRuleVersion(grant.getRuleVersion());
        view.setGrantedAt(grant.getGrantedAt());
        return view;
    }
}
