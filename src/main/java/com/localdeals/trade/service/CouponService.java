package com.localdeals.trade.service;

import com.localdeals.merchant.dto.AdminPrincipal;
import com.localdeals.trade.entity.UserCoupon;
import org.springframework.stereotype.Service;

import java.util.List;

/** Verification at the shop, expiry, and the user's coupon wallet. */
@Service
public class CouponService {

    /** Consumes the coupon behind {@code verifyCode} for the principal's merchant. */
    public UserCoupon verify(String verifyCode, AdminPrincipal principal) {
        throw new UnsupportedOperationException("M2: not implemented yet");
    }

    /** Marks up to {@code limit} lapsed AVAILABLE coupons EXPIRED; returns how many. */
    public int expireDue(int limit) {
        throw new UnsupportedOperationException("M2: not implemented yet");
    }

    public List<UserCoupon> listMine(long userId) {
        throw new UnsupportedOperationException("M2: not implemented yet");
    }
}
