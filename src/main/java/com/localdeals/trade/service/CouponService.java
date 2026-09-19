package com.localdeals.trade.service;

import com.localdeals.merchant.dto.AdminPrincipal;
import com.localdeals.platform.exception.ApiErrorCodes;
import com.localdeals.platform.exception.ApiStatusException;
import com.localdeals.trade.entity.CouponSource;
import com.localdeals.trade.entity.OrderEvent;
import com.localdeals.trade.entity.UserCoupon;
import com.localdeals.trade.mapper.UserCouponMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** Verification at the shop, expiry, and the user's coupon wallet. */
@Service
public class CouponService {

    private static final Pattern CODE = Pattern.compile("[0-9A-Z]{16}");
    private static final int WALLET_LIMIT = 200;

    private final UserCouponMapper couponMapper;
    private final OrderStateMachine stateMachine;

    public CouponService(UserCouponMapper couponMapper, OrderStateMachine stateMachine) {
        this.couponMapper = couponMapper;
        this.stateMachine = stateMachine;
    }

    /**
     * Consumes the coupon behind {@code verifyCode} for the principal's merchant. For a purchase
     * the order moves PAID -> USED first: locking the order row before the coupon row is the same
     * order a refund uses, so verify and refund serialise instead of deadlocking, and exactly one
     * of them wins.
     */
    @Transactional
    public UserCoupon verify(String verifyCode, AdminPrincipal principal) {
        String code = verifyCode == null ? "" : verifyCode.trim().toUpperCase(Locale.ROOT);
        UserCoupon coupon = CODE.matcher(code).matches() ? couponMapper.selectByVerifyCode(code) : null;
        // Unknown and foreign codes get the same answer: a merchant cannot probe other merchants' codes.
        if (coupon == null || (!principal.isPlatform() && !principal.getMerchantId().equals(coupon.getMerchantId()))) {
            throw new ApiStatusException(HttpStatus.NOT_FOUND, ApiErrorCodes.COUPON_INVALID, "券码无效");
        }
        long adminId = principal.getAccountId();
        if (coupon.getSource() == CouponSource.PURCHASE &&
                !stateMachine.fire(Long.parseLong(coupon.getSourceRef()), OrderEvent.VERIFY, "ADMIN:" + adminId)) {
            throw rejection(couponMapper.selectForUpdate(coupon.getId()));
        }
        if (couponMapper.markUsed(coupon.getId(), adminId) != 1) {
            // Rolls back the order transition above.
            throw rejection(couponMapper.selectForUpdate(coupon.getId()));
        }
        return couponMapper.selectById(coupon.getId());
    }

    /** Marks up to {@code limit} lapsed AVAILABLE coupons EXPIRED; returns how many. */
    @Transactional
    public int expireDue(int limit) {
        return couponMapper.expireDue(limit);
    }

    public List<UserCoupon> listMine(long userId) {
        return couponMapper.selectByUser(userId, WALLET_LIMIT);
    }

    private static ApiStatusException rejection(UserCoupon latest) {
        switch (latest.getStatus()) {
            case USED:
                return new ApiStatusException(HttpStatus.CONFLICT, ApiErrorCodes.COUPON_ALREADY_USED, "券已核销");
            case AVAILABLE:
                // Still AVAILABLE but the conditional update refused it: outside the validity window.
            case EXPIRED:
                return new ApiStatusException(HttpStatus.CONFLICT, ApiErrorCodes.COUPON_EXPIRED, "券已过期");
            default:
                return new ApiStatusException(HttpStatus.CONFLICT, ApiErrorCodes.COUPON_NOT_USABLE,
                        "券正在退款或已退款");
        }
    }
}
