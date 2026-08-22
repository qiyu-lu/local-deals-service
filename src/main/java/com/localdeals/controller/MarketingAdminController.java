package com.localdeals.controller;

import com.localdeals.auth.AdminPermissionCodes;
import com.localdeals.auth.RequireAdminPermission;
import com.localdeals.dto.AdminVoucherGrantRequest;
import com.localdeals.dto.MarketingTagMemberRequest;
import com.localdeals.dto.MarketingTagRequest;
import com.localdeals.dto.Result;
import com.localdeals.dto.VoucherCampaignRequest;
import com.localdeals.dto.VoucherCampaignStatusRequest;
import com.localdeals.dto.VoucherGrantCommand;
import com.localdeals.service.MarketingAdminService;
import com.localdeals.service.VoucherGrantService;
import com.localdeals.utils.AdminPrincipalHolder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/admin/marketing")
public class MarketingAdminController {
    private final MarketingAdminService marketingAdminService;
    private final VoucherGrantService grantService;

    public MarketingAdminController(MarketingAdminService marketingAdminService,
            VoucherGrantService grantService) {
        this.marketingAdminService = marketingAdminService;
        this.grantService = grantService;
    }

    @GetMapping("/tags")
    @RequireAdminPermission(AdminPermissionCodes.MARKETING_READ)
    public Result listTags(@RequestParam(value = "merchantId", required = false) Long merchantId) {
        return Result.ok(marketingAdminService.listTags(merchantId));
    }

    @PostMapping("/tags")
    @RequireAdminPermission(AdminPermissionCodes.MARKETING_WRITE)
    public Result createTag(@RequestBody MarketingTagRequest request) {
        return Result.ok(marketingAdminService.createTag(request));
    }

    @GetMapping("/tags/{tagId}/members")
    @RequireAdminPermission(AdminPermissionCodes.MARKETING_READ)
    public Result listMembers(@PathVariable("tagId") Long tagId,
            @RequestParam(value = "merchantId", required = false) Long merchantId) {
        return Result.ok(marketingAdminService.listMembers(tagId, merchantId));
    }

    @PostMapping("/tags/{tagId}/members/{userId}")
    @RequireAdminPermission(AdminPermissionCodes.MARKETING_WRITE)
    public Result addMember(@PathVariable("tagId") Long tagId,
            @PathVariable("userId") Long userId,
            @RequestBody MarketingTagMemberRequest request) {
        return Result.ok(marketingAdminService.addMember(tagId, userId, request));
    }

    @DeleteMapping("/tags/{tagId}/members/{userId}")
    @RequireAdminPermission(AdminPermissionCodes.MARKETING_WRITE)
    public Result removeMember(@PathVariable("tagId") Long tagId,
            @PathVariable("userId") Long userId,
            @RequestParam(value = "merchantId", required = false) Long merchantId) {
        marketingAdminService.removeMember(tagId, userId, merchantId);
        return Result.ok();
    }

    @GetMapping("/campaigns")
    @RequireAdminPermission(AdminPermissionCodes.MARKETING_READ)
    public Result listCampaigns(@RequestParam(value = "merchantId", required = false) Long merchantId) {
        return Result.ok(marketingAdminService.listCampaigns(merchantId));
    }

    @GetMapping("/campaigns/{campaignId}")
    @RequireAdminPermission(AdminPermissionCodes.MARKETING_READ)
    public Result getCampaign(@PathVariable("campaignId") Long campaignId,
            @RequestParam(value = "merchantId", required = false) Long merchantId) {
        return Result.ok(marketingAdminService.getCampaign(campaignId, merchantId));
    }

    @PostMapping("/campaigns")
    @RequireAdminPermission(AdminPermissionCodes.MARKETING_WRITE)
    public Result createCampaign(@RequestBody VoucherCampaignRequest request) {
        return Result.ok(marketingAdminService.createCampaign(request));
    }

    @PutMapping("/campaigns/{campaignId}")
    @RequireAdminPermission(AdminPermissionCodes.MARKETING_WRITE)
    public Result updateCampaign(@PathVariable("campaignId") Long campaignId,
            @RequestBody VoucherCampaignRequest request) {
        return Result.ok(marketingAdminService.updateCampaign(campaignId, request));
    }

    @PutMapping("/campaigns/{campaignId}/status")
    @RequireAdminPermission(AdminPermissionCodes.MARKETING_WRITE)
    public Result changeCampaignStatus(@PathVariable("campaignId") Long campaignId,
            @RequestBody VoucherCampaignStatusRequest request) {
        return Result.ok(marketingAdminService.changeStatus(campaignId, request));
    }

    @PostMapping("/campaigns/{campaignId}/grants")
    @RequireAdminPermission(AdminPermissionCodes.MARKETING_WRITE)
    public Result grant(@PathVariable("campaignId") Long campaignId,
            @RequestBody AdminVoucherGrantRequest request) {
        if (request == null) throw new IllegalArgumentException("发放信息不能为空");
        VoucherGrantCommand command = new VoucherGrantCommand();
        command.setCampaignId(campaignId);
        command.setMerchantId(marketingAdminService.resolveMerchant(request.getMerchantId()));
        command.setUserId(request.getUserId());
        command.setExpectedRuleVersion(request.getExpectedRuleVersion());
        command.setOperatorId(AdminPrincipalHolder.get().getAccountId());
        command.setSource(VoucherGrantCommand.ADMIN_GRANT);
        return Result.ok(grantService.grant(command));
    }

    @GetMapping("/campaigns/{campaignId}/grants")
    @RequireAdminPermission(AdminPermissionCodes.MARKETING_READ)
    public Result listGrants(@PathVariable("campaignId") Long campaignId,
            @RequestParam(value = "merchantId", required = false) Long merchantId) {
        Long scopedMerchantId = marketingAdminService.resolveMerchant(merchantId);
        return Result.ok(grantService.listCampaignGrants(campaignId, scopedMerchantId));
    }
}
