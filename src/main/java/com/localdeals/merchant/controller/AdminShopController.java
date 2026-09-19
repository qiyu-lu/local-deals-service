package com.localdeals.merchant.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.localdeals.merchant.auth.AdminPermissionCodes;
import com.localdeals.merchant.auth.RequireAdminPermission;
import com.localdeals.merchant.dto.AdminShopAssignmentRequest;
import com.localdeals.merchant.dto.AdminShopCreateRequest;
import com.localdeals.merchant.dto.AdminShopUpdateRequest;
import com.localdeals.merchant.dto.AdminVoucherCreateRequest;
import com.localdeals.platform.dto.Result;
import com.localdeals.merchant.entity.Shop;
import com.localdeals.merchant.service.AdminCatalogService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/admin/shops")
public class AdminShopController {
    private final AdminCatalogService adminCatalogService;

    public AdminShopController(AdminCatalogService adminCatalogService) {
        this.adminCatalogService = adminCatalogService;
    }

    @GetMapping
    @RequireAdminPermission(AdminPermissionCodes.SHOP_READ)
    public Result list(@RequestParam(value = "current", defaultValue = "1") int current,
            @RequestParam(value = "size", defaultValue = "20") int size,
            @RequestParam(value = "keyword", required = false) String keyword) {
        Page<Shop> page = adminCatalogService.listShops(current, size, keyword);
        return Result.ok(page.getRecords(), page.getTotal());
    }

    @GetMapping("/{id}")
    @RequireAdminPermission(AdminPermissionCodes.SHOP_READ)
    public Result get(@PathVariable("id") Long id) {
        return Result.ok(adminCatalogService.getShop(id));
    }

    @PostMapping
    @RequireAdminPermission(AdminPermissionCodes.SHOP_WRITE)
    public Result create(@RequestBody AdminShopCreateRequest request) {
        return Result.ok(adminCatalogService.createShop(request));
    }

    @PutMapping("/{id}")
    @RequireAdminPermission(AdminPermissionCodes.SHOP_WRITE)
    public Result update(@PathVariable("id") Long id,
            @RequestBody AdminShopUpdateRequest request) {
        return Result.ok(adminCatalogService.updateShop(id, request));
    }

    @PutMapping("/{id}/merchant")
    @RequireAdminPermission(AdminPermissionCodes.MERCHANT_MANAGE)
    public Result assignMerchant(@PathVariable("id") Long id,
            @RequestBody AdminShopAssignmentRequest request) {
        adminCatalogService.assignShop(id, request == null ? null : request.getMerchantId());
        return Result.ok();
    }

    @GetMapping("/{shopId}/vouchers")
    @RequireAdminPermission(AdminPermissionCodes.VOUCHER_READ)
    public Result listVouchers(@PathVariable("shopId") Long shopId) {
        return Result.ok(adminCatalogService.listVouchers(shopId));
    }

    @PostMapping("/{shopId}/vouchers")
    @RequireAdminPermission(AdminPermissionCodes.VOUCHER_WRITE)
    public Result createVoucher(@PathVariable("shopId") Long shopId,
            @RequestBody AdminVoucherCreateRequest request) {
        return Result.ok(adminCatalogService.createVoucher(shopId, request, false));
    }

    @PostMapping("/{shopId}/vouchers/seckill")
    @RequireAdminPermission(AdminPermissionCodes.VOUCHER_WRITE)
    public Result createSeckillVoucher(@PathVariable("shopId") Long shopId,
            @RequestBody AdminVoucherCreateRequest request) {
        return Result.ok(adminCatalogService.createVoucher(shopId, request, true));
    }
}
