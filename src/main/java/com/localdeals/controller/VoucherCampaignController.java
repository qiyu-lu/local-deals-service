package com.localdeals.controller;

import com.localdeals.dto.Result;
import com.localdeals.dto.UserDTO;
import com.localdeals.dto.VoucherGrantClaimRequest;
import com.localdeals.exception.ApiStatusException;
import com.localdeals.service.VoucherCampaignUserService;
import com.localdeals.utils.UserHolder;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/voucher-campaigns")
public class VoucherCampaignController {
    private final VoucherCampaignUserService userService;

    public VoucherCampaignController(VoucherCampaignUserService userService) {
        this.userService = userService;
    }

    @GetMapping("/shop/{shopId}")
    public Result listForShop(@PathVariable("shopId") Long shopId) {
        return Result.ok(userService.listForShop(shopId, currentUserId()));
    }

    @PostMapping("/{campaignId}/claim")
    public Result claim(@PathVariable("campaignId") Long campaignId,
            @RequestBody VoucherGrantClaimRequest request) {
        return Result.ok(userService.claim(campaignId, request, currentUserId()));
    }

    private Long currentUserId() {
        UserDTO user = UserHolder.getUser();
        if (user == null || user.getId() == null) {
            throw new ApiStatusException(HttpStatus.UNAUTHORIZED, "登录已失效");
        }
        return user.getId();
    }
}
