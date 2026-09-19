package com.localdeals.trade.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.localdeals.merchant.audit.AdminAuditController;
import com.localdeals.merchant.audit.AdminAuditService;
import com.localdeals.merchant.auth.AdminPermissionCodes;
import com.localdeals.merchant.dto.AdminPrincipal;
import com.localdeals.merchant.service.AdminSessionService;
import com.localdeals.platform.config.WebConfig;
import com.localdeals.platform.config.WebExceptionAdvice;
import com.localdeals.platform.exception.ApiErrorCodes;
import com.localdeals.platform.exception.ApiStatusException;
import com.localdeals.trade.entity.UserCoupon;
import com.localdeals.trade.service.AdminOrderService;
import com.localdeals.trade.service.CouponService;
import com.localdeals.trade.service.CouponVerifyRateLimiter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = {AdminCouponController.class, AdminOrderController.class, AdminAuditController.class})
@ContextConfiguration(classes = AdminTradeMvcTest.MvcTestConfiguration.class)
class AdminTradeMvcTest {

    private static final String STAFF = "staff00000000000000000000000000a";
    private static final String OWNER = "owner00000000000000000000000000b";
    private static final String NO_ORDERS = "none000000000000000000000000000c";

    @Autowired
    private MockMvc mockMvc;
    @MockitoBean
    private AdminSessionService sessionService;
    @MockitoBean
    private StringRedisTemplate redisTemplate;
    @MockitoBean
    private CouponService couponService;
    @MockitoBean
    private CouponVerifyRateLimiter rateLimiter;
    @MockitoBean
    private AdminOrderService orderService;
    @MockitoBean
    private AdminAuditService auditService;

    @BeforeEach
    void setUp() {
        session(STAFF, AdminPermissionCodes.COUPON_VERIFY, AdminPermissionCodes.ORDER_READ);
        session(OWNER, AdminPermissionCodes.COUPON_VERIFY, AdminPermissionCodes.ORDER_READ,
                AdminPermissionCodes.AUDIT_READ);
        session(NO_ORDERS, AdminPermissionCodes.SHOP_READ);
        UserCoupon used = new UserCoupon();
        used.setCouponNo("P42");
        when(couponService.verify(eq("ABCDEFGHJKMNPQRS"), any())).thenReturn(used);
        when(orderService.list(any(), any(), eq(1), eq(20))).thenReturn(new Page<>(1, 20));
        when(auditService.list(any(), eq(1), eq(20))).thenReturn(new Page<>(1, 20));
    }

    @Test
    void staffVerifyAfterTheRateLimiterAgreed() throws Exception {
        mockMvc.perform(post("/admin/coupons/verify").header("Authorization", "Bearer " + STAFF)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"verifyCode\":\"ABCDEFGHJKMNPQRS\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.couponNo").value("P42"));

        verify(rateLimiter).acquire(any());
    }

    @Test
    void aMerchantOverItsVerifyBudgetGets429AndNoLookup() throws Exception {
        doThrow(new ApiStatusException(HttpStatus.TOO_MANY_REQUESTS, ApiErrorCodes.COUPON_VERIFY_RATE_LIMITED, "slow"))
                .when(rateLimiter).acquire(any());

        mockMvc.perform(post("/admin/coupons/verify").header("Authorization", "Bearer " + STAFF)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"verifyCode\":\"ABCDEFGHJKMNPQRS\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value(ApiErrorCodes.COUPON_VERIFY_RATE_LIMITED));
        verify(couponService, never()).verify(any(), any());
    }

    @Test
    void verifyingNeedsTheCouponVerifyPermission() throws Exception {
        mockMvc.perform(post("/admin/coupons/verify").header("Authorization", "Bearer " + NO_ORDERS)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"verifyCode\":\"ABCDEFGHJKMNPQRS\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void theOrderListIsReadableWithOrderRead() throws Exception {
        mockMvc.perform(get("/admin/orders").header("Authorization", "Bearer " + STAFF))
                .andExpect(status().isOk());
        mockMvc.perform(get("/admin/orders").header("Authorization", "Bearer " + NO_ORDERS))
                .andExpect(status().isForbidden());
    }

    @Test
    void theAuditLogIsForOwnersNotStaff() throws Exception {
        mockMvc.perform(get("/admin/audit-logs").header("Authorization", "Bearer " + OWNER))
                .andExpect(status().isOk());
        mockMvc.perform(get("/admin/audit-logs").header("Authorization", "Bearer " + STAFF))
                .andExpect(status().isForbidden());
    }

    private void session(String token, String... permissions) {
        AdminPrincipal principal = new AdminPrincipal();
        principal.setAccountId(7L);
        principal.setMerchantId(22L);
        principal.setScopeType(AdminPrincipal.SCOPE_MERCHANT);
        principal.setPermissions(Set.of(permissions));
        when(sessionService.extractBearerToken("Bearer " + token)).thenReturn(token);
        when(sessionService.resolve(token, true)).thenReturn(principal);
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import({WebConfig.class, WebExceptionAdvice.class, AdminCouponController.class, AdminOrderController.class,
            AdminAuditController.class})
    static class MvcTestConfiguration {
    }
}
