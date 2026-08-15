package com.localdeals.interctptor;

import com.localdeals.config.AdminProperties;
import com.localdeals.dto.UserDTO;
import com.localdeals.utils.UserHolder;
import org.springframework.http.HttpMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * Protects management mutations with a temporary administrator allowlist.
 * Database-backed roles and merchant data scopes will replace this boundary in the RBAC phase.
 */
public class AdminAccessInterceptor implements HandlerInterceptor {

    private final AdminProperties adminProperties;

    public AdminAccessInterceptor(AdminProperties adminProperties) {
        this.adminProperties = adminProperties;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (isSafeMethod(request.getMethod())) {
            return true;
        }
        UserDTO user = UserHolder.getUser();
        if (user == null) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            return false;
        }
        if (!adminProperties.isAdminUser(user.getId())) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            return false;
        }
        return true;
    }

    private boolean isSafeMethod(String method) {
        return HttpMethod.GET.matches(method) || HttpMethod.HEAD.matches(method) ||
                HttpMethod.OPTIONS.matches(method);
    }
}
