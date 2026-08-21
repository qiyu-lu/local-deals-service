package com.localdeals.controller;

import com.localdeals.auth.AdminPermissionCodes;
import com.localdeals.auth.RequireAdminPermission;
import com.localdeals.dto.AdminLoginRequest;
import com.localdeals.dto.AdminPasswordChangeRequest;
import com.localdeals.dto.AdminWebSocketTicketResponse;
import com.localdeals.dto.Result;
import com.localdeals.exception.ApiStatusException;
import com.localdeals.service.AdminAuthService;
import com.localdeals.service.TrustedClientIpResolver;
import com.localdeals.service.AdminSessionService;
import com.localdeals.utils.AdminPrincipalHolder;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;

@RestController
@RequestMapping("/admin/auth")
@RequireAdminPermission
public class AdminAuthController {
    private final AdminAuthService adminAuthService;
    private final AdminSessionService adminSessionService;
    private final TrustedClientIpResolver clientIpResolver;

    public AdminAuthController(AdminAuthService adminAuthService,
            AdminSessionService adminSessionService,
            TrustedClientIpResolver clientIpResolver) {
        this.adminAuthService = adminAuthService;
        this.adminSessionService = adminSessionService;
        this.clientIpResolver = clientIpResolver;
    }

    @PostMapping("/login")
    public Result login(@RequestBody AdminLoginRequest request, HttpServletRequest servletRequest) {
        return Result.ok(adminAuthService.login(request, clientIpResolver.resolve(servletRequest)));
    }

    @GetMapping("/me")
    public Result me() {
        return Result.ok(AdminPrincipalHolder.get());
    }

    @PostMapping("/logout")
    public Result logout(@RequestHeader("Authorization") String authorization) {
        adminAuthService.logout(adminSessionService.extractBearerToken(authorization));
        return Result.ok();
    }

    @PutMapping("/password")
    public Result changePassword(@RequestBody AdminPasswordChangeRequest request) {
        adminAuthService.changePassword(AdminPrincipalHolder.get(), request);
        return Result.ok();
    }

    @PostMapping("/ws-ticket")
    @RequireAdminPermission(AdminPermissionCodes.ORDER_REALTIME)
    public Result issueWebSocketTicket(@RequestHeader("Authorization") String authorization) {
        String token = adminSessionService.extractBearerToken(authorization);
        AdminWebSocketTicketResponse ticket = adminSessionService.issueWebSocketTicket(token);
        if (ticket == null) {
            throw new ApiStatusException(HttpStatus.UNAUTHORIZED, "后台登录已失效");
        }
        return Result.ok(ticket);
    }
}
