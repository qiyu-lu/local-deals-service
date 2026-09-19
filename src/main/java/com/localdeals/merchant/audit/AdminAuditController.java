package com.localdeals.merchant.audit;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.localdeals.merchant.auth.AdminPermissionCodes;
import com.localdeals.merchant.auth.RequireAdminPermission;
import com.localdeals.merchant.utils.AdminPrincipalHolder;
import com.localdeals.platform.dto.Result;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/admin/audit-logs")
public class AdminAuditController {

    private final AdminAuditService auditService;

    public AdminAuditController(AdminAuditService auditService) {
        this.auditService = auditService;
    }

    @GetMapping
    @RequireAdminPermission(AdminPermissionCodes.AUDIT_READ)
    public Result list(@RequestParam(value = "current", defaultValue = "1") int current,
                       @RequestParam(value = "size", defaultValue = "20") int size) {
        Page<AdminAuditLog> page = auditService.list(AdminPrincipalHolder.get(), current, size);
        return Result.ok(page.getRecords(), page.getTotal());
    }
}
