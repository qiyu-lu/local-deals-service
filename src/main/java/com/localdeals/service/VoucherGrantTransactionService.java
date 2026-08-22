package com.localdeals.service;

import com.localdeals.dto.VoucherGrantCommand;
import com.localdeals.entity.MarketingTag;
import com.localdeals.entity.MarketingTagMember;
import com.localdeals.entity.VoucherCampaign;
import com.localdeals.entity.VoucherGrant;
import com.localdeals.exception.ApiStatusException;
import com.localdeals.mapper.MarketingTagMapper;
import com.localdeals.mapper.MarketingTagMemberMapper;
import com.localdeals.mapper.VoucherCampaignMapper;
import com.localdeals.mapper.VoucherGrantMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
public class VoucherGrantTransactionService {
    private final VoucherCampaignMapper campaignMapper;
    private final VoucherGrantMapper grantMapper;
    private final MarketingTagMapper tagMapper;
    private final MarketingTagMemberMapper memberMapper;

    public VoucherGrantTransactionService(VoucherCampaignMapper campaignMapper,
            VoucherGrantMapper grantMapper, MarketingTagMapper tagMapper,
            MarketingTagMemberMapper memberMapper) {
        this.campaignMapper = campaignMapper;
        this.grantMapper = grantMapper;
        this.tagMapper = tagMapper;
        this.memberMapper = memberMapper;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public VoucherGrant grant(VoucherGrantCommand command) {
        VoucherGrant existing = selectExisting(command);
        if (existing != null) return existing;

        VoucherCampaign campaign = lockCampaign(command);
        if (campaign == null) throw new ApiStatusException(HttpStatus.NOT_FOUND, "活动不存在");
        if (!command.getExpectedRuleVersion().equals(campaign.getRuleVersion())) {
            throw conflict("CAMPAIGN_RULE_CHANGED", "活动规则版本已变化");
        }
        validateSource(campaign, command.getSource());
        LocalDateTime now = campaignMapper.currentDatabaseTime();
        validateWindow(campaign, now);
        if (!Integer.valueOf(0).equals(campaign.getVoucherType()) ||
                !Integer.valueOf(1).equals(campaign.getVoucherStatus()) ||
                !campaign.getMerchantId().equals(campaign.getVoucherMerchantId())) {
            throw conflict("CAMPAIGN_RULE_CHANGED", "活动绑定券已变化");
        }

        if ("MANUAL_TAG".equals(campaign.getEligibilityType())) {
            lockAndValidateTagMember(campaign, command.getUserId(), now);
        }
        if (campaign.getGrantedCount() >= campaign.getQuotaTotal()) {
            throw conflict("CAMPAIGN_QUOTA_EXHAUSTED", "活动额度已用尽");
        }
        if (campaignMapper.incrementGrantCount(campaign.getId(), campaign.getMerchantId(),
                campaign.getVoucherId(), campaign.getRuleVersion()) != 1) {
            classifyConditionalFailure(campaign, command.getExpectedRuleVersion(), now);
        }

        VoucherGrant grant = new VoucherGrant();
        grant.setCampaignId(campaign.getId());
        grant.setMerchantId(campaign.getMerchantId());
        grant.setVoucherId(campaign.getVoucherId());
        grant.setUserId(command.getUserId());
        grant.setSource(command.getSource());
        grant.setRuleVersion(campaign.getRuleVersion());
        grant.setOperatorId(command.getOperatorId());
        if (grantMapper.insert(grant) != 1) throw new IllegalStateException("发券流水写入失败");
        return selectExisting(command);
    }

    private VoucherGrant selectExisting(VoucherGrantCommand command) {
        if (VoucherGrantCommand.ADMIN_GRANT.equals(command.getSource())) {
            return grantMapper.selectByCampaignAndMerchantAndUser(command.getCampaignId(),
                    command.getMerchantId(), command.getUserId());
        }
        return grantMapper.selectByCampaignAndUser(command.getCampaignId(), command.getUserId());
    }

    private VoucherCampaign lockCampaign(VoucherGrantCommand command) {
        if (VoucherGrantCommand.ADMIN_GRANT.equals(command.getSource())) {
            return campaignMapper.selectScopedForUpdate(command.getCampaignId(), command.getMerchantId());
        }
        return campaignMapper.selectForUserClaimForUpdate(command.getCampaignId());
    }

    private void validateSource(VoucherCampaign campaign, String source) {
        if (VoucherGrantCommand.USER_CLAIM.equals(source) &&
                !"CLAIM".equals(campaign.getGrantMode()) && !"BOTH".equals(campaign.getGrantMode())) {
            throw conflict("CAMPAIGN_GRANT_MODE_UNSUPPORTED", "活动不支持用户领取");
        }
        if (VoucherGrantCommand.ADMIN_GRANT.equals(source) &&
                !"ADMIN".equals(campaign.getGrantMode()) && !"BOTH".equals(campaign.getGrantMode())) {
            throw conflict("CAMPAIGN_GRANT_MODE_UNSUPPORTED", "活动不支持管理员发放");
        }
    }

    private void validateWindow(VoucherCampaign campaign, LocalDateTime now) {
        if (!"ACTIVE".equals(campaign.getStatus())) {
            throw conflict("CAMPAIGN_NOT_ACTIVE", "活动未处于 ACTIVE 状态");
        }
        if (now.isBefore(campaign.getBeginTime())) {
            throw conflict("CAMPAIGN_NOT_STARTED", "活动尚未开始");
        }
        if (!now.isBefore(campaign.getEndTime())) {
            throw conflict("CAMPAIGN_ENDED", "活动已结束");
        }
    }

    private void lockAndValidateTagMember(VoucherCampaign campaign, Long userId, LocalDateTime now) {
        // Campaign -> tag -> member is also the order used by tag membership writes.
        MarketingTag tag = tagMapper.selectScopedForUpdate(campaign.getRequiredTagId(), campaign.getMerchantId());
        MarketingTagMember member = memberMapper.selectScopedForUpdate(campaign.getMerchantId(),
                campaign.getRequiredTagId(), userId);
        if (tag == null || !"ACTIVE".equals(tag.getStatus()) || member == null ||
                !"ACTIVE".equals(member.getStatus()) ||
                (member.getExpireTime() != null && !member.getExpireTime().isAfter(now))) {
            throw conflict("CAMPAIGN_INELIGIBLE", "用户不满足活动标签条件");
        }
    }

    private void classifyConditionalFailure(VoucherCampaign campaign, Long expectedRuleVersion,
            LocalDateTime now) {
        if (!expectedRuleVersion.equals(campaign.getRuleVersion())) {
            throw conflict("CAMPAIGN_RULE_CHANGED", "活动规则版本已变化");
        }
        validateWindow(campaign, now);
        if (campaign.getGrantedCount() >= campaign.getQuotaTotal()) {
            throw conflict("CAMPAIGN_QUOTA_EXHAUSTED", "活动额度已用尽");
        }
        throw conflict("CAMPAIGN_RULE_CHANGED", "活动规则已变化");
    }

    private ApiStatusException conflict(String code, String message) {
        return new ApiStatusException(HttpStatus.CONFLICT, code, message);
    }
}
