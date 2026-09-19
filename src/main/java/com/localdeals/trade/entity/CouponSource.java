package com.localdeals.trade.entity;

/** Where a coupon asset came from; {@code source_ref} is the order number or the grant id. */
public enum CouponSource {
    PURCHASE,
    CLAIM,
    ADMIN_GRANT,
    TASK_REWARD,
    BATCH_GRANT;

    /** Maps a {@code tb_voucher_grant.source} value to the coupon source. */
    public static CouponSource ofGrantSource(String grantSource) {
        switch (grantSource) {
            case "USER_CLAIM":
                return CLAIM;
            case "ADMIN_GRANT":
                return ADMIN_GRANT;
            case "TASK_REWARD":
                return TASK_REWARD;
            case "BATCH_GRANT":
                return BATCH_GRANT;
            default:
                throw new IllegalArgumentException("Unknown grant source: " + grantSource);
        }
    }
}
