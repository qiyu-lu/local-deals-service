package com.localdeals.controller;

import com.localdeals.auth.AdminPermissionCodes;
import com.localdeals.auth.RequireAdminPermission;
import com.localdeals.dto.AdminAccountCreateRequest;
import com.localdeals.dto.AdminAccountStatusRequest;
import com.localdeals.dto.AdminMerchantCreateRequest;
import com.localdeals.dto.Result;
import com.localdeals.service.AdminManagementService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/admin")
public class AdminManagementController {
    private final AdminManagementService adminManagementService;

    public AdminManagementController(AdminManagementService adminManagementService) {
        this.adminManagementService = adminManagementService;
    }

    @GetMapping("/merchants")
    @RequireAdminPermission(AdminPermissionCodes.MERCHANT_MANAGE)
    public Result listMerchants() {
        return Result.ok(adminManagementService.listMerchants());
    }

    @PostMapping("/merchants")
    @RequireAdminPermission(AdminPermissionCodes.MERCHANT_MANAGE)
    public Result createMerchant(@RequestBody AdminMerchantCreateRequest request) {
        return Result.ok(adminManagementService.createMerchant(request));
    }

    @GetMapping("/accounts")
    @RequireAdminPermission(AdminPermissionCodes.ACCOUNT_READ)
    public Result listAccounts(@RequestParam(value = "merchantId", required = false) Long merchantId) {
        return Result.ok(adminManagementService.listAccounts(merchantId));
    }

    @PostMapping("/accounts")
    @RequireAdminPermission(AdminPermissionCodes.ACCOUNT_WRITE)
    public Result createStaff(@RequestBody AdminAccountCreateRequest request) {
        return Result.ok(adminManagementService.createStaff(request));
    }

    @PutMapping("/accounts/{id}/status")
    @RequireAdminPermission(AdminPermissionCodes.ACCOUNT_WRITE)
    public Result changeAccountStatus(@PathVariable("id") Long id,
            @RequestBody AdminAccountStatusRequest request) {
        adminManagementService.changeAccountStatus(id, request == null ? null : request.getStatus());
        return Result.ok();
    }
}
