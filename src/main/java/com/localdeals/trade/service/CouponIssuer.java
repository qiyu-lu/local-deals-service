package com.localdeals.trade.service;

import com.localdeals.marketing.entity.VoucherGrant;
import com.localdeals.trade.entity.CouponSource;
import com.localdeals.trade.entity.TradeOrder;
import com.localdeals.trade.entity.UserCoupon;
import com.localdeals.trade.mapper.UserCouponMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

/**
 * Issues the coupon asset inside the caller's transaction: a paid order and a marketing grant
 * each produce exactly one coupon. The coupon number is derived from its source (P<order_no>,
 * G<grant_id>), so the unique key itself makes issuance idempotent; the verify code is the
 * random secret shown to the user.
 */
@Service
public class CouponIssuer {

    private static final int MAX_CODE_ATTEMPTS = 3;

    private final UserCouponMapper couponMapper;
    private final VerifyCodeGenerator codeGenerator;

    public CouponIssuer(UserCouponMapper couponMapper, VerifyCodeGenerator codeGenerator) {
        this.couponMapper = couponMapper;
        this.codeGenerator = codeGenerator;
    }

    public UserCoupon issueForOrder(TradeOrder order) {
        return issue("P" + order.getOrderNo(), order.getUserId(), order.getVoucherId(), order.getMerchantId(),
                CouponSource.PURCHASE, order.getOrderNo().toString());
    }

    public UserCoupon issueForGrant(VoucherGrant grant) {
        return issue("G" + grant.getId(), grant.getUserId(), grant.getVoucherId(), grant.getMerchantId(),
                CouponSource.ofGrantSource(grant.getSource()), grant.getId().toString());
    }

    private UserCoupon issue(String couponNo, long userId, long voucherId, long merchantId,
                             CouponSource source, String sourceRef) {
        for (int attempt = 0; attempt < MAX_CODE_ATTEMPTS; attempt++) {
            try {
                if (couponMapper.insertFromVoucher(couponNo, userId, voucherId, merchantId, source.name(),
                        sourceRef, codeGenerator.next()) != 1) {
                    throw new IllegalStateException("Voucher is missing; cannot issue coupon " + couponNo);
                }
                return couponMapper.selectByCouponNo(couponNo);
            } catch (DuplicateKeyException duplicate) {
                UserCoupon existing = couponMapper.selectByCouponNo(couponNo);
                if (existing != null) {
                    return existing;
                }
                // Otherwise the random verify code collided with another coupon: draw again.
            }
        }
        throw new IllegalStateException("No unique verify code after " + MAX_CODE_ATTEMPTS + " attempts");
    }
}
