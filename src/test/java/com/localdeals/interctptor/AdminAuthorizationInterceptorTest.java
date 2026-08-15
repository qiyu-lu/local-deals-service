package com.localdeals.interctptor;

import com.localdeals.auth.RequireAdminPermission;
import com.localdeals.dto.AdminPrincipal;
import com.localdeals.utils.AdminPrincipalHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.method.HandlerMethod;

import java.lang.reflect.Method;
import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;

class AdminAuthorizationInterceptorTest {
    private final AdminAuthorizationInterceptor interceptor = new AdminAuthorizationInterceptor();
    private final FixtureController controller = new FixtureController();

    @AfterEach
    void clearHolder() {
        AdminPrincipalHolder.remove();
    }

    @Test
    void anonymousAdminRequestIsUnauthorized() throws Exception {
        MockHttpServletResponse response = invoke("protectedEndpoint");

        assertThat(response.getStatus()).isEqualTo(401);
    }

    @Test
    void authenticatedRequestWithoutExplicitAnnotationIsDeniedByDefault() throws Exception {
        loginWith("shop:read");

        MockHttpServletResponse response = invoke("unannotatedEndpoint");

        assertThat(response.getStatus()).isEqualTo(403);
    }

    @Test
    void missingPermissionIsForbidden() throws Exception {
        loginWith("shop:read");

        MockHttpServletResponse response = invoke("protectedEndpoint");

        assertThat(response.getStatus()).isEqualTo(403);
    }

    @Test
    void exactPermissionAllowsRequest() throws Exception {
        loginWith("shop:write");
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/admin/fixture");
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean accepted = interceptor.preHandle(
                request, response, handler("protectedEndpoint"));

        assertThat(accepted).isTrue();
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void authenticatedOnlyAnnotationDoesNotGrantAnUnrelatedPermission() throws Exception {
        loginWith("shop:read");
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/admin/fixture/me");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThat(interceptor.preHandle(
                request, response, handler("authenticatedEndpoint"))).isTrue();
    }

    private MockHttpServletResponse invoke(String methodName) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/admin/fixture");
        MockHttpServletResponse response = new MockHttpServletResponse();
        assertThat(interceptor.preHandle(request, response, handler(methodName))).isFalse();
        return response;
    }

    private HandlerMethod handler(String methodName) throws Exception {
        Method method = FixtureController.class.getDeclaredMethod(methodName);
        return new HandlerMethod(controller, method);
    }

    private void loginWith(String permission) {
        AdminPrincipal principal = new AdminPrincipal();
        principal.setAccountId(7L);
        principal.setScopeType(AdminPrincipal.SCOPE_MERCHANT);
        principal.setMerchantId(11L);
        principal.setPermissions(Collections.singleton(permission));
        AdminPrincipalHolder.save(principal);
    }

    static class FixtureController {
        @RequireAdminPermission("shop:write")
        public void protectedEndpoint() {
        }

        @RequireAdminPermission
        public void authenticatedEndpoint() {
        }

        public void unannotatedEndpoint() {
        }
    }
}
