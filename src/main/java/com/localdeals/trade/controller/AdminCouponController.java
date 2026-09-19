package com.localdeals.trade.controller;

import com.localdeals.merchant.audit.AdminAudit;
import com.localdeals.merchant.auth.AdminPermissionCodes;
import com.localdeals.merchant.auth.RequireAdminPermission;
import com.localdeals.merchant.dto.AdminPrincipal;
import com.localdeals.merchant.utils.AdminPrincipalHolder;
import com.localdeals.platform.dto.Result;
import com.localdeals.trade.dto.CouponVerifyRequest;
import com.localdeals.trade.service.CouponService;
import com.localdeals.trade.service.CouponVerifyRateLimiter;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** In-store verification: the staff enters (or scans) the code the customer shows. */
@RestController
@RequestMapping("/admin/coupons")
public class AdminCouponController {

    private final CouponService couponService;
    private final CouponVerifyRateLimiter rateLimiter;

    public AdminCouponController(CouponService couponService, CouponVerifyRateLimiter rateLimiter) {
        this.couponService = couponService;
        this.rateLimiter = rateLimiter;
    }

    @PostMapping("/verify")
    @RequireAdminPermission(AdminPermissionCodes.COUPON_VERIFY)
    // The audit keeps the coupon number, never the verify code itself.
    @AdminAudit(action = "COUPON_VERIFY", targetType = "COUPON", target = "#result.data.couponNo")
    public Result verify(@RequestBody CouponVerifyRequest request) {
        AdminPrincipal principal = AdminPrincipalHolder.get();
        rateLimiter.acquire(principal);
        return Result.ok(couponService.verify(request == null ? null : request.getVerifyCode(), principal));
    }
}
