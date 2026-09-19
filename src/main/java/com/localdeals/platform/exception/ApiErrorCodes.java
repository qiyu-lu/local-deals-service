package com.localdeals.platform.exception;

/** Stable, finite API error codes introduced by the M5C traffic contract. */
public final class ApiErrorCodes {
    public static final String INVALID_REQUEST = "INVALID_REQUEST";
    public static final String INTERNAL_ERROR = "INTERNAL_ERROR";
    public static final String OTP_RATE_LIMITED = "OTP_RATE_LIMITED";
    public static final String AUTH_STATE_UNAVAILABLE = "AUTH_STATE_UNAVAILABLE";
    public static final String ADMIN_LOGIN_RATE_LIMITED = "ADMIN_LOGIN_RATE_LIMITED";
    public static final String ADMIN_LOGIN_UNAVAILABLE = "ADMIN_LOGIN_UNAVAILABLE";
    public static final String SECKILL_RATE_LIMITED = "SECKILL_RATE_LIMITED";
    public static final String SECKILL_BUSY = "SECKILL_BUSY";
    public static final String SECKILL_OUT_OF_STOCK = "SECKILL_OUT_OF_STOCK";
    public static final String SECKILL_DUPLICATE = "SECKILL_DUPLICATE";
    public static final String SECKILL_NOT_STARTED = "SECKILL_NOT_STARTED";
    public static final String SECKILL_ENDED = "SECKILL_ENDED";
    public static final String SECKILL_STATE_UNAVAILABLE = "SECKILL_STATE_UNAVAILABLE";
    public static final String SECKILL_SUBMIT_UNAVAILABLE = "SECKILL_SUBMIT_UNAVAILABLE";
    public static final String READ_OVERLOADED = "READ_OVERLOADED";
    public static final String SEARCH_OVERLOADED = "SEARCH_OVERLOADED";
    public static final String DATABASE_UNAVAILABLE = "DATABASE_UNAVAILABLE";
    public static final String SEARCH_UNAVAILABLE = "SEARCH_UNAVAILABLE";
    public static final String TASK_NOT_COMPLETED = "TASK_NOT_COMPLETED";
    public static final String COUPON_INVALID = "COUPON_INVALID";
    public static final String COUPON_ALREADY_USED = "COUPON_ALREADY_USED";
    public static final String COUPON_EXPIRED = "COUPON_EXPIRED";
    public static final String COUPON_NOT_USABLE = "COUPON_NOT_USABLE";
    public static final String COUPON_VERIFY_RATE_LIMITED = "COUPON_VERIFY_RATE_LIMITED";
    public static final String ORDER_NOT_FOUND = "ORDER_NOT_FOUND";
    public static final String ORDER_NOT_PAYABLE = "ORDER_NOT_PAYABLE";
    public static final String ORDER_ALREADY_USED = "ORDER_ALREADY_USED";
    public static final String ORDER_NOT_REFUNDABLE = "ORDER_NOT_REFUNDABLE";
    public static final String REFUND_IN_PROGRESS = "REFUND_IN_PROGRESS";
    public static final String PAYMENT_SIGNATURE_INVALID = "PAYMENT_SIGNATURE_INVALID";
    public static final String PAYMENT_NOT_FOUND = "PAYMENT_NOT_FOUND";

    private ApiErrorCodes() {
    }
}
