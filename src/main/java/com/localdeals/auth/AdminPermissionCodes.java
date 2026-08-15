package com.localdeals.auth;

public final class AdminPermissionCodes {
    public static final String DASHBOARD_READ = "dashboard:read";
    public static final String SHOP_READ = "shop:read";
    public static final String SHOP_WRITE = "shop:write";
    public static final String VOUCHER_READ = "voucher:read";
    public static final String VOUCHER_WRITE = "voucher:write";
    public static final String ORDER_READ = "order:read";
    public static final String ORDER_REALTIME = "order:realtime";
    public static final String ACCOUNT_READ = "account:read";
    public static final String ACCOUNT_WRITE = "account:manage";
    public static final String MERCHANT_MANAGE = "merchant:manage";

    private AdminPermissionCodes() {
    }
}
