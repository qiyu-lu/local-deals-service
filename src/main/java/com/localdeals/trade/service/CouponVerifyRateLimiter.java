package com.localdeals.trade.service;

import com.localdeals.merchant.dto.AdminPrincipal;
import com.localdeals.platform.exception.ApiErrorCodes;
import com.localdeals.platform.exception.ApiStatusException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.util.Collections;

/**
 * Per-merchant cap on verification attempts (fixed one-minute window), so a merchant account
 * cannot sweep the code space. Together with 80-bit codes this makes guessing hopeless; the cap
 * mainly stops a leaked back-office account from being turned into an oracle.
 */
@Slf4j
@Component
public class CouponVerifyRateLimiter {

    private static final DefaultRedisScript<Long> WINDOW_SCRIPT = new DefaultRedisScript<>();

    static {
        WINDOW_SCRIPT.setLocation(new ClassPathResource("lua/coupon_verify_rate.lua"));
        WINDOW_SCRIPT.setResultType(Long.class);
    }

    private final StringRedisTemplate redis;
    private final int limitPerMinute;

    public CouponVerifyRateLimiter(StringRedisTemplate redis,
            @Value("${local-deals.coupon.verify-limit-per-minute:120}") int limitPerMinute) {
        this.redis = redis;
        this.limitPerMinute = limitPerMinute;
    }

    /** Throws 429 when the merchant used up this minute's attempts. */
    public void acquire(AdminPrincipal principal) {
        String scope = principal.isPlatform() ? "platform" : principal.getMerchantId().toString();
        String key = "coupon:verify:rate:" + scope + ":" + System.currentTimeMillis() / 60_000L;
        final Long count;
        try {
            count = redis.execute(WINDOW_SCRIPT, Collections.singletonList(key), "120");
        } catch (RuntimeException e) {
            // Fail open: a Redis hiccup must not stop shops from serving customers.
            log.warn("Coupon verify rate limiter unavailable; allowing. scope={}", scope, e);
            return;
        }
        if (count != null && count > limitPerMinute) {
            throw new ApiStatusException(HttpStatus.TOO_MANY_REQUESTS, ApiErrorCodes.COUPON_VERIFY_RATE_LIMITED,
                    "核销尝试过于频繁，请稍后再试");
        }
    }
}
