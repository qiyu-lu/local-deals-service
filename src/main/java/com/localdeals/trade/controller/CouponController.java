package com.localdeals.trade.controller;

import com.localdeals.platform.dto.Result;
import com.localdeals.platform.utils.UserHolder;
import com.localdeals.trade.service.CouponService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The user's coupon wallet, including the verify codes to show at the shop. */
@RestController
@RequestMapping("/coupons")
public class CouponController {

    private final CouponService couponService;

    public CouponController(CouponService couponService) {
        this.couponService = couponService;
    }

    @GetMapping
    public Result mine() {
        return Result.ok(couponService.listMine(UserHolder.getUser().getId()));
    }
}
