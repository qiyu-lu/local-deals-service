package com.localdeals.service;

import com.localdeals.dto.AdminPrincipal;
import com.localdeals.dto.MarketingTagMemberRequest;
import com.localdeals.dto.MarketingTagRequest;
import com.localdeals.dto.VoucherCampaignRequest;
import com.localdeals.dto.VoucherCampaignStatusRequest;
import com.localdeals.entity.MarketingTag;
import com.localdeals.entity.MarketingTagMember;
import com.localdeals.entity.VoucherCampaign;
import com.localdeals.exception.ApiStatusException;
import com.localdeals.mapper.MarketingTagMapper;
import com.localdeals.mapper.MarketingTagMemberMapper;
import com.localdeals.mapper.VoucherCampaignMapper;
import com.localdeals.utils.AdminPrincipalHolder;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

@Service
public class MarketingAdminService {
    private static final Pattern TAG_CODE = Pattern.compile("[A-Z0-9_-]{2,64}");
    private static final Set<String> GRANT_MODES = new HashSet<>(Arrays.asList("CLAIM", "ADMIN", "BOTH"));
    private static final Set<String> ELIGIBILITY_TYPES = new HashSet<>(Arrays.asList("ALL", "MANUAL_TAG"));
    private static final Set<String> STATUSES = new HashSet<>(Arrays.asList("DRAFT", "ACTIVE", "PAUSED", "CLOSED"));

    private final MarketingTagMapper tagMapper;
    private final MarketingTagMemberMapper memberMapper;
    private final VoucherCampaignMapper campaignMapper;

    public MarketingAdminService(MarketingTagMapper tagMapper,
            MarketingTagMemberMapper memberMapper, VoucherCampaignMapper campaignMapper) {
        this.tagMapper = tagMapper;
        this.memberMapper = memberMapper;
        this.campaignMapper = campaignMapper;
    }

    public List<MarketingTag> listTags(Long requestedMerchantId) {
        return tagMapper.selectAllScoped(resolveMerchant(requestedMerchantId));
    }

    public List<MarketingTagMember> listMembers(Long tagId, Long requestedMerchantId) {
        if (tagId == null) throw new IllegalArgumentException("标签不能为空");
        Long merchantId = resolveMerchant(requestedMerchantId);
        if (tagMapper.selectScoped(tagId, merchantId) == null) throw notFound();
        return memberMapper.selectAllScoped(merchantId, tagId);
    }

    @Transactional
    public MarketingTag createTag(MarketingTagRequest request) {
        if (request == null) throw new IllegalArgumentException("标签信息不能为空");
        Long merchantId = resolveMerchant(request.getMerchantId());
        String code = normalize(request.getCode());
        if (code == null || !TAG_CODE.matcher(code).matches()) {
            throw new IllegalArgumentException("标签编码仅支持 2-64 位大写字母、数字、下划线和短横线");
        }
        String name = requiredName(request.getName(), "标签名称");
        MarketingTag tag = new MarketingTag();
        tag.setMerchantId(merchantId);
        tag.setCode(code);
        tag.setName(name);
        tag.setStatus("ACTIVE");
        tag.setCreatedBy(currentPrincipal().getAccountId());
        try {
            if (tagMapper.insert(tag) != 1) throw new IllegalStateException("标签创建失败");
        } catch (DuplicateKeyException duplicate) {
            throw new IllegalArgumentException("当前商户已存在相同标签编码");
        }
        return tagMapper.selectScoped(tag.getId(), merchantId);
    }

    @Transactional
    public MarketingTagMember addMember(Long tagId, Long userId,
            MarketingTagMemberRequest request) {
        if (tagId == null || userId == null || request == null) {
            throw new IllegalArgumentException("标签、用户和成员信息不能为空");
        }
        Long merchantId = resolveMerchant(request.getMerchantId());
        MarketingTag tag = tagMapper.selectScopedForUpdate(tagId, merchantId);
        if (tag == null) throw notFound();
        if (!"ACTIVE".equals(tag.getStatus())) throw new IllegalArgumentException("标签已停用");
        if (request.getExpireTime() != null && !request.getExpireTime().isAfter(LocalDateTime.now())) {
            throw new IllegalArgumentException("成员过期时间必须晚于当前时间");
        }
        if (memberMapper.countBusinessRelationship(merchantId, userId) == 0) {
            throw new ApiStatusException(HttpStatus.CONFLICT, "CAMPAIGN_INELIGIBLE",
                    "用户与当前商户尚无业务关系");
        }
        memberMapper.upsertActive(merchantId, tagId, userId, request.getExpireTime(),
                currentPrincipal().getAccountId());
        return memberMapper.selectScopedForUpdate(merchantId, tagId, userId);
    }

    @Transactional
    public void removeMember(Long tagId, Long userId, Long requestedMerchantId) {
        if (tagId == null || userId == null) throw new IllegalArgumentException("标签和用户不能为空");
        Long merchantId = resolveMerchant(requestedMerchantId);
        if (tagMapper.selectScopedForUpdate(tagId, merchantId) == null) throw notFound();
        MarketingTagMember member = memberMapper.selectScopedForUpdate(merchantId, tagId, userId);
        if (member != null && "ACTIVE".equals(member.getStatus())) {
            memberMapper.removeScoped(merchantId, tagId, userId);
        }
    }

    public List<VoucherCampaign> listCampaigns(Long requestedMerchantId) {
        return campaignMapper.selectAllScoped(resolveMerchant(requestedMerchantId));
    }

    public VoucherCampaign getCampaign(Long campaignId, Long requestedMerchantId) {
        if (campaignId == null) throw new IllegalArgumentException("活动不能为空");
        Long merchantId = resolveMerchant(requestedMerchantId);
        VoucherCampaign campaign = campaignMapper.selectScoped(campaignId, merchantId);
        if (campaign == null) throw notFound();
        return campaign;
    }

    @Transactional
    public VoucherCampaign createCampaign(VoucherCampaignRequest request) {
        if (request == null) throw new IllegalArgumentException("活动信息不能为空");
        Long merchantId = resolveMerchant(request.getMerchantId());
        VoucherCampaign campaign = campaignFrom(request, merchantId);
        campaign.setStatus("DRAFT");
        campaign.setGrantedCount(0);
        campaign.setRuleVersion(1L);
        campaign.setCreatedBy(currentPrincipal().getAccountId());
        if (campaignMapper.insert(campaign) != 1) throw new IllegalStateException("活动创建失败");
        return campaignMapper.selectScoped(campaign.getId(), merchantId);
    }

    @Transactional
    public VoucherCampaign updateCampaign(Long campaignId, VoucherCampaignRequest request) {
        if (campaignId == null || request == null) throw new IllegalArgumentException("活动信息不能为空");
        Long merchantId = resolveMerchant(request.getMerchantId());
        requireExpectedRuleVersion(request.getExpectedRuleVersion());
        String expectedStatus = normalize(request.getExpectedStatus());
        if (!isEditableStatus(expectedStatus)) {
            throw new IllegalArgumentException("expected status 只允许 DRAFT 或 PAUSED");
        }
        VoucherCampaign existing = campaignMapper.selectScopedForUpdate(campaignId, merchantId);
        if (existing == null) throw notFound();
        if (!expectedStatus.equals(existing.getStatus())) {
            throw new ApiStatusException(HttpStatus.CONFLICT, "CAMPAIGN_RULE_CHANGED", "活动状态或规则版本已变化");
        }
        if (!isEditableStatus(existing.getStatus())) throw new IllegalArgumentException("仅 DRAFT 或 PAUSED 活动可编辑");
        if (existing.getGrantedCount() != null && existing.getGrantedCount() > 0 &&
                !existing.getVoucherId().equals(request.getVoucherId())) {
            throw new IllegalArgumentException("活动已有发放记录后不可修改绑定券");
        }
        VoucherCampaign patch = campaignFrom(request, merchantId);
        patch.setId(campaignId);
        patch.setExpectedStatus(expectedStatus);
        patch.setExpectedRuleVersion(request.getExpectedRuleVersion());
        if (campaignMapper.updateDefinitionScoped(patch) != 1) {
            throw new ApiStatusException(HttpStatus.CONFLICT, "CAMPAIGN_RULE_CHANGED",
                    "活动已变化或新额度小于已发放数量");
        }
        return campaignMapper.selectScoped(campaignId, merchantId);
    }

    @Transactional
    public VoucherCampaign changeStatus(Long campaignId, VoucherCampaignStatusRequest request) {
        if (campaignId == null || request == null) throw new IllegalArgumentException("活动状态信息不能为空");
        Long merchantId = resolveMerchant(request.getMerchantId());
        String expectedStatus = normalize(request.getExpectedStatus());
        String status = normalize(request.getStatus());
        if (!STATUSES.contains(status) || "DRAFT".equals(status)) {
            throw new IllegalArgumentException("活动状态只允许 ACTIVE、PAUSED 或 CLOSED");
        }
        if (!STATUSES.contains(expectedStatus) || request.getExpectedRuleVersion() == null) {
            throw new IllegalArgumentException("expected status 和 expected ruleVersion 不能为空");
        }
        VoucherCampaign existing = campaignMapper.selectScopedForUpdate(campaignId, merchantId);
        if (existing == null) throw notFound();
        if (!expectedStatus.equals(existing.getStatus()) ||
                !request.getExpectedRuleVersion().equals(existing.getRuleVersion())) {
            throw new ApiStatusException(HttpStatus.CONFLICT, "CAMPAIGN_RULE_CHANGED", "活动状态或规则版本已变化");
        }
        if (!allowedTransition(existing.getStatus(), status)) {
            throw new IllegalArgumentException("不允许的活动状态流转");
        }
        validateVoucherAndTag(existing, merchantId);
        if (campaignMapper.updateStatusScoped(campaignId, merchantId, expectedStatus,
                request.getExpectedRuleVersion(), status) != 1) {
            throw new ApiStatusException(HttpStatus.CONFLICT, "CAMPAIGN_RULE_CHANGED", "活动状态已变化");
        }
        return campaignMapper.selectScoped(campaignId, merchantId);
    }

    public Long resolveMerchant(Long requestedMerchantId) {
        AdminPrincipal principal = currentPrincipal();
        if (principal.isPlatform()) {
            if (requestedMerchantId == null) {
                throw new IllegalArgumentException("平台操作必须显式指定 merchantId");
            }
            return requestedMerchantId;
        }
        if (principal.getMerchantId() == null) throw new ApiStatusException(HttpStatus.FORBIDDEN, "商户范围缺失");
        if (requestedMerchantId != null && !principal.getMerchantId().equals(requestedMerchantId)) {
            throw new ApiStatusException(HttpStatus.FORBIDDEN, "禁止跨商户操作");
        }
        return principal.getMerchantId();
    }

    private VoucherCampaign campaignFrom(VoucherCampaignRequest request, Long merchantId) {
        VoucherCampaign campaign = new VoucherCampaign();
        campaign.setMerchantId(merchantId);
        campaign.setVoucherId(request.getVoucherId());
        campaign.setName(requiredName(request.getName(), "活动名称"));
        campaign.setGrantMode(normalize(request.getGrantMode()));
        campaign.setEligibilityType(normalize(request.getEligibilityType()));
        campaign.setRequiredTagId(request.getRequiredTagId());
        campaign.setBeginTime(request.getBeginTime());
        campaign.setEndTime(request.getEndTime());
        campaign.setQuotaTotal(request.getQuotaTotal());
        validateDefinition(campaign);
        validateVoucherAndTag(campaign, merchantId);
        return campaign;
    }

    private void validateDefinition(VoucherCampaign campaign) {
        if (campaign.getVoucherId() == null || !GRANT_MODES.contains(campaign.getGrantMode()) ||
                !ELIGIBILITY_TYPES.contains(campaign.getEligibilityType()) ||
                campaign.getBeginTime() == null || campaign.getEndTime() == null ||
                !campaign.getBeginTime().isBefore(campaign.getEndTime()) ||
                campaign.getQuotaTotal() == null || campaign.getQuotaTotal() <= 0) {
            throw new IllegalArgumentException("券、模式、资格、时间窗或额度不合法");
        }
        if (("MANUAL_TAG".equals(campaign.getEligibilityType())) !=
                (campaign.getRequiredTagId() != null)) {
            throw new IllegalArgumentException("MANUAL_TAG 必须且只能绑定一个标签");
        }
    }

    private void requireExpectedRuleVersion(Long expectedRuleVersion) {
        if (expectedRuleVersion == null || expectedRuleVersion < 1) {
            throw new IllegalArgumentException("expected ruleVersion 不能为空");
        }
    }

    private boolean isEditableStatus(String status) {
        return "DRAFT".equals(status) || "PAUSED".equals(status);
    }

    private void validateVoucherAndTag(VoucherCampaign campaign, Long merchantId) {
        if (campaignMapper.countActiveRegularVoucherScoped(campaign.getVoucherId(), merchantId) != 1) {
            throw new IllegalArgumentException("活动只能绑定当前商户已上架的普通券");
        }
        if (campaign.getRequiredTagId() != null) {
            MarketingTag tag = tagMapper.selectScoped(campaign.getRequiredTagId(), merchantId);
            if (tag == null || !"ACTIVE".equals(tag.getStatus())) {
                throw new IllegalArgumentException("活动标签不存在、跨商户或已停用");
            }
        }
    }

    private boolean allowedTransition(String from, String to) {
        if (from.equals(to)) return true;
        if ("DRAFT".equals(from)) return "ACTIVE".equals(to) || "CLOSED".equals(to);
        if ("ACTIVE".equals(from)) return "PAUSED".equals(to) || "CLOSED".equals(to);
        if ("PAUSED".equals(from)) return "ACTIVE".equals(to) || "CLOSED".equals(to);
        return false;
    }

    private String requiredName(String value, String label) {
        if (!StringUtils.hasText(value) || value.trim().length() > 128) {
            throw new IllegalArgumentException(label + "不能为空且不能超过 128 个字符");
        }
        return value.trim();
    }

    private String normalize(String value) {
        return StringUtils.hasText(value) ? value.trim().toUpperCase(Locale.ROOT) : null;
    }

    private AdminPrincipal currentPrincipal() {
        AdminPrincipal principal = AdminPrincipalHolder.get();
        if (principal == null) throw new ApiStatusException(HttpStatus.UNAUTHORIZED, "后台登录已失效");
        return principal;
    }

    private ApiStatusException notFound() {
        return new ApiStatusException(HttpStatus.NOT_FOUND, "资源不存在");
    }
}
