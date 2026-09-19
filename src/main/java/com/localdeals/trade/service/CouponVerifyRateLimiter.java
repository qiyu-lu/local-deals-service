package com.localdeals.trade.service;

import com.localdeals.merchant.dto.AdminPrincipal;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/** Per-merchant cap on verification attempts, so codes cannot be enumerated at the counter. */
@Component
public class CouponVerifyRateLimiter {

    private final StringRedisTemplate redis;
    private final int limitPerMinute;

    public CouponVerifyRateLimiter(StringRedisTemplate redis,
            @org.springframework.beans.factory.annotation.Value("${local-deals.coupon.verify-limit-per-minute:120}")
            int limitPerMinute) {
        this.redis = redis;
        this.limitPerMinute = limitPerMinute;
    }

    /** Throws 429 when the merchant used up this minute's attempts. */
    public void acquire(AdminPrincipal principal) {
        throw new UnsupportedOperationException("M2: not implemented yet");
    }
}
