package com.localdeals.trade.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.localdeals.merchant.auth.AdminPermissionCodes;
import com.localdeals.merchant.auth.RequireAdminPermission;
import com.localdeals.merchant.utils.AdminPrincipalHolder;
import com.localdeals.platform.dto.Result;
import com.localdeals.trade.entity.TradeOrder;
import com.localdeals.trade.service.AdminOrderService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/admin/orders")
public class AdminOrderController {

    private final AdminOrderService adminOrderService;

    public AdminOrderController(AdminOrderService adminOrderService) {
        this.adminOrderService = adminOrderService;
    }

    @GetMapping
    @RequireAdminPermission(AdminPermissionCodes.ORDER_READ)
    public Result list(@RequestParam(value = "status", required = false) String status,
                       @RequestParam(value = "current", defaultValue = "1") int current,
                       @RequestParam(value = "size", defaultValue = "20") int size) {
        Page<TradeOrder> page = adminOrderService.list(AdminPrincipalHolder.get(), status, current, size);
        return Result.ok(page.getRecords(), page.getTotal());
    }
}
