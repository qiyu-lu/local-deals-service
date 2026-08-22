package com.localdeals.exception;

/** Stable, finite API error codes introduced by the M5C traffic contract. */
public final class ApiErrorCodes {
    public static final String INVALID_REQUEST = "INVALID_REQUEST";
    public static final String INTERNAL_ERROR = "INTERNAL_ERROR";
    public static final String OTP_RATE_LIMITED = "OTP_RATE_LIMITED";
    public static final String AUTH_STATE_UNAVAILABLE = "AUTH_STATE_UNAVAILABLE";
    public static final String ADMIN_LOGIN_RATE_LIMITED = "ADMIN_LOGIN_RATE_LIMITED";
    public static final String ADMIN_LOGIN_UNAVAILABLE = "ADMIN_LOGIN_UNAVAILABLE";
    public static final String SECKILL_RATE_LIMITED = "SECKILL_RATE_LIMITED";
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

    private ApiErrorCodes() {
    }
}
