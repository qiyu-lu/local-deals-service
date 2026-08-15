package com.localdeals.interctptor;

import com.localdeals.dto.AdminPrincipal;
import com.localdeals.service.AdminSessionService;
import com.localdeals.utils.AdminPrincipalHolder;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

public class AdminSessionInterceptor implements HandlerInterceptor {
    private final AdminSessionService adminSessionService;

    public AdminSessionInterceptor(AdminSessionService adminSessionService) {
        this.adminSessionService = adminSessionService;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String token = adminSessionService.extractBearerToken(request.getHeader("Authorization"));
        if (token == null) {
            return true;
        }
        AdminPrincipal principal = adminSessionService.resolve(token, true);
        if (principal != null) {
            AdminPrincipalHolder.save(principal);
        }
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
            Object handler, Exception ex) {
        AdminPrincipalHolder.remove();
    }
}
