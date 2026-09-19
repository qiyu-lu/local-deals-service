package com.localdeals.trade.service;

import com.localdeals.marketing.entity.VoucherGrant;
import com.localdeals.trade.entity.TradeOrder;
import com.localdeals.trade.entity.UserCoupon;
import com.localdeals.trade.mapper.UserCouponMapper;
import org.springframework.stereotype.Service;

/**
 * Issues the coupon asset inside the caller's transaction: a paid order and a marketing grant
 * each produce exactly one coupon, keyed by a deterministic coupon number.
 */
@Service
public class CouponIssuer {

    private final UserCouponMapper couponMapper;
    private final VerifyCodeGenerator codeGenerator;

    public CouponIssuer(UserCouponMapper couponMapper, VerifyCodeGenerator codeGenerator) {
        this.couponMapper = couponMapper;
        this.codeGenerator = codeGenerator;
    }

    public UserCoupon issueForOrder(TradeOrder order) {
        throw new UnsupportedOperationException("M2: not implemented yet");
    }

    public UserCoupon issueForGrant(VoucherGrant grant) {
        throw new UnsupportedOperationException("M2: not implemented yet");
    }
}
