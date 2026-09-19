package com.localdeals.trade.service;

import com.localdeals.merchant.dto.AdminPrincipal;
import com.localdeals.platform.exception.ApiErrorCodes;
import com.localdeals.platform.exception.ApiStatusException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CouponVerifyRateLimiterTest {

    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private final CouponVerifyRateLimiter limiter = new CouponVerifyRateLimiter(redis, 3);

    @Test
    @SuppressWarnings("unchecked")
    void attemptsAreCountedPerMerchantPerMinute() {
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenReturn(3L);

        assertThatCode(() -> limiter.acquire(merchant(22L))).doesNotThrowAnyException();

        ArgumentCaptor<List<String>> keys = ArgumentCaptor.forClass(List.class);
        verify(redis).execute(any(RedisScript.class), keys.capture(), any(Object[].class));
        assertThat(keys.getValue().get(0)).startsWith("coupon:verify:rate:22:");
    }

    @Test
    void overTheLimitIsRejectedWith429() {
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenReturn(4L);

        assertThatThrownBy(() -> limiter.acquire(merchant(22L)))
                .isInstanceOfSatisfying(ApiStatusException.class, error -> {
                    assertThat(error.getStatus().value()).isEqualTo(429);
                    assertThat(error.getCode()).isEqualTo(ApiErrorCodes.COUPON_VERIFY_RATE_LIMITED);
                });
    }

    @Test
    void aRedisOutageDoesNotStopVerificationAtTheCounter() {
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .thenThrow(new RedisConnectionFailureException("down"));

        assertThatCode(() -> limiter.acquire(merchant(22L))).doesNotThrowAnyException();
    }

    private static AdminPrincipal merchant(long merchantId) {
        AdminPrincipal principal = new AdminPrincipal();
        principal.setAccountId(1L);
        principal.setMerchantId(merchantId);
        principal.setScopeType(AdminPrincipal.SCOPE_MERCHANT);
        return principal;
    }
}
