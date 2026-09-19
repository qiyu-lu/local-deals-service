package com.localdeals.merchant.audit;

import com.localdeals.merchant.auth.RequireAdminPermission;
import com.localdeals.merchant.dto.AdminPrincipal;
import com.localdeals.merchant.utils.AdminPrincipalHolder;
import com.localdeals.platform.dto.Result;
import com.localdeals.platform.exception.ApiStatusException;
import com.localdeals.platform.service.TrustedClientIpResolver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.servlet.HandlerMapping;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AdminAuditAspectTest {

    private final AdminAuditService auditService = mock(AdminAuditService.class);
    private final TrustedClientIpResolver ipResolver = mock(TrustedClientIpResolver.class);
    private FakeController controller;
    private MockHttpServletRequest request;

    @BeforeEach
    void setUp() {
        AspectJProxyFactory factory = new AspectJProxyFactory(new FakeController());
        factory.setProxyTargetClass(true);
        factory.addAspect(new AdminAuditAspect(auditService, ipResolver));
        controller = factory.getProxy();
        when(ipResolver.resolve(any())).thenReturn("203.0.113.7");

        AdminPrincipal principal = new AdminPrincipal();
        principal.setAccountId(11L);
        principal.setMerchantId(22L);
        principal.setUsername("staff.a");
        principal.setScopeType(AdminPrincipal.SCOPE_MERCHANT);
        AdminPrincipalHolder.save(principal);
    }

    @AfterEach
    void tearDown() {
        AdminPrincipalHolder.remove();
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void aSuccessfulWriteIsRecordedWithWhoWhatWhichAndWhere() {
        route("POST", "/admin/marketing/campaigns/{campaignId}/status", Map.of("campaignId", "5"));

        controller.write();

        AdminAuditLog log = recorded();
        assertThat(log.getAccountId()).isEqualTo(11L);
        assertThat(log.getMerchantId()).isEqualTo(22L);
        assertThat(log.getUsername()).isEqualTo("staff.a");
        assertThat(log.getAction()).isEqualTo("POST /admin/marketing/campaigns/{campaignId}/status");
        assertThat(log.getTargetId()).isEqualTo("campaignId=5");
        assertThat(log.getResult()).isEqualTo(AdminAuditLog.SUCCESS);
        assertThat(log.getClientIp()).isEqualTo("203.0.113.7");
    }

    @Test
    void readsAreNotAudited() {
        route("GET", "/admin/shops", Map.of());

        controller.write();

        verify(auditService, never()).record(any());
    }

    @Test
    void aRejectedWriteIsRecordedAsFailureAndStillThrown() {
        route("PUT", "/admin/accounts/{id}/status", Map.of("id", "9"));

        assertThatThrownBy(() -> controller.reject()).isInstanceOf(ApiStatusException.class);

        AdminAuditLog log = recorded();
        assertThat(log.getResult()).isEqualTo(AdminAuditLog.FAILURE);
        assertThat(log.getErrorCode()).isEqualTo("CAMPAIGN_RULE_CHANGED");
    }

    @Test
    void aFailedResultBodyIsAFailureToo() {
        route("POST", "/admin/x", Map.of());

        controller.failInBody();

        assertThat(recorded().getErrorCode()).isEqualTo("SOME_CODE");
    }

    @Test
    void anExplicitActionAndResultTargetOverrideTheDefaults() {
        route("POST", "/admin/coupons/verify", Map.of());

        controller.verify();

        AdminAuditLog log = recorded();
        assertThat(log.getAction()).isEqualTo("COUPON_VERIFY");
        assertThat(log.getTargetType()).isEqualTo("COUPON");
        assertThat(log.getTargetId()).isEqualTo("P42");
    }

    @Test
    void anAuditStoreOutageDoesNotBreakTheOperation() {
        route("POST", "/admin/x", Map.of());
        doThrow(new IllegalStateException("db down")).when(auditService).record(any());

        assertThat(controller.write().getSuccess()).isTrue();
    }

    private AdminAuditLog recorded() {
        ArgumentCaptor<AdminAuditLog> log = ArgumentCaptor.forClass(AdminAuditLog.class);
        verify(auditService).record(log.capture());
        return log.getValue();
    }

    private void route(String method, String pattern, Map<String, String> variables) {
        request = new MockHttpServletRequest(method, pattern);
        request.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, pattern);
        request.setAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE, variables);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    public static class FakeController {
        @RequireAdminPermission("x:write")
        public Result write() {
            return Result.ok();
        }

        @RequireAdminPermission("x:write")
        public Result reject() {
            throw new ApiStatusException(HttpStatus.CONFLICT, "CAMPAIGN_RULE_CHANGED", "changed");
        }

        @RequireAdminPermission("x:write")
        public Result failInBody() {
            return Result.fail("SOME_CODE", "nope");
        }

        @RequireAdminPermission("coupon:verify")
        @AdminAudit(action = "COUPON_VERIFY", targetType = "COUPON", target = "#result.data.couponNo")
        public Result verify() {
            return Result.ok(Map.of("couponNo", "P42"));
        }
    }
}
