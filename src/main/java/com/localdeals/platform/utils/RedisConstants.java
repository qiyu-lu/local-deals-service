package com.localdeals.platform.utils;

public class RedisConstants {
    public static final String LOGIN_CODE_KEY = "login:code:";
    public static final Long LOGIN_CODE_TTL = 2L;
    public static final String LOGIN_CODE_RATE_LIMIT_KEY = "login:code:rate:";
    public static final Long LOGIN_CODE_RATE_LIMIT_TTL = 60L;
    public static final String LOGIN_CODE_FAILURE_KEY = "login:code:failure:";
    public static final Long LOGIN_CODE_MAX_FAILURES = 5L;
    public static final String LOGIN_USER_KEY = "login:token:";

    public static final String ADMIN_LOGIN_TOKEN_KEY = "admin:login:token:";
    public static final String ADMIN_LOGIN_FAILURE_KEY = "admin:login:failure:";
    public static final String ADMIN_LOGIN_IP_ATTEMPT_KEY = "admin:login:ip-attempt:";
    public static final String ADMIN_WEBSOCKET_TICKET_KEY = "admin:ws:ticket:";

    public static final String CACHE_SHOP_KEY = "cache:shop:";

    public static final String CACHE_SHOP_TYPE_LIST_KEY = "cache:shop:type:list:";

    public static final String EMPTY_PLACEHOLDER = "_NULL_PLACEHOLDER_";

    public static final String SECKILL_STOCK_KEY = "seckill:stock:";
    /** Activity metadata Hash: status, beginAt and endAt (epoch seconds). */
    public static final String SECKILL_META_KEY = "seckill:meta:";
    /** Per-voucher reservation Hash: userId -> exact orderId. */
    public static final String SECKILL_RESERVATION_KEY = "seckill:reservation:";
    /** Per-order status Hash: PROCESSING, SUCCESS or FAILED plus ownership metadata. */
    public static final String SECKILL_ORDER_STATUS_KEY = "seckill:order:status:";
    /** Global due-time index. Members are exact decimal order-id strings; scores are epoch seconds. */
    public static final String SECKILL_PROCESSING_INDEX_KEY = "seckill:order:processing";
    /** Shared lock namespace used by both the MQ consumer and the reconciliation worker. */
    public static final String SECKILL_ORDER_LOCK_KEY = "lock:order:";
    /** Per-order scheduler arbitration lock; losers must not move the winner's due score. */
    public static final String SECKILL_RECONCILIATION_LOCK_KEY = "lock:seckill:reconcile:";
    public static final Long SECKILL_ORDER_STATUS_TTL_SECONDS = 7 * 24 * 60 * 60L;
    public static final String BLOG_LIKE_OUTBOX_LOCK_KEY = "lock:blog:like:outbox";

    public static final String SHOP_GEO_KEY = "shop:geo:";

}
