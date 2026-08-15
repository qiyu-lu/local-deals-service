package com.localdeals.interctptor;

import com.localdeals.auth.RequireAdminPermission;
import com.localdeals.dto.AdminPrincipal;
import com.localdeals.utils.AdminPrincipalHolder;
import org.springframework.http.HttpMethod;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/** Every /admin endpoint must opt in with an explicit annotation. */
public class AdminAuthorizationInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (HttpMethod.OPTIONS.matches(request.getMethod())) {
            return true;
        }
        AdminPrincipal principal = AdminPrincipalHolder.get();
        if (principal == null) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            return false;
        }
        if (!(handler instanceof HandlerMethod)) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            return false;
        }

        HandlerMethod handlerMethod = (HandlerMethod) handler;
        RequireAdminPermission requirement = handlerMethod.getMethodAnnotation(RequireAdminPermission.class);
        if (requirement == null) {
            requirement = handlerMethod.getBeanType().getAnnotation(RequireAdminPermission.class);
        }
        if (requirement == null) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            return false;
        }
        String permission = requirement.value();
        if (!permission.isEmpty() && !principal.hasPermission(permission)) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            return false;
        }
        return true;
    }
}
