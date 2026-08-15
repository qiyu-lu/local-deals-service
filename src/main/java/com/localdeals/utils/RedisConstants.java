package com.localdeals.utils;

public class RedisConstants {
    public static final String LOGIN_CODE_KEY = "login:code:";
    public static final Long LOGIN_CODE_TTL = 2L;
    public static final String LOGIN_CODE_RATE_LIMIT_KEY = "login:code:rate:";
    public static final Long LOGIN_CODE_RATE_LIMIT_TTL = 60L;
    public static final String LOGIN_CODE_FAILURE_KEY = "login:code:failure:";
    public static final Long LOGIN_CODE_MAX_FAILURES = 5L;
    public static final String LOGIN_USER_KEY = "login:token:";
    public static final Long LOGIN_USER_TTL = 36000L;

    public static final Long CACHE_NULL_TTL = 2L;

    public static final Long CACHE_SHOP_TTL = 30L;
    public static final String CACHE_SHOP_KEY = "cache:shop:";

    public static final Long CACHE_SHOP_TYPE_LIST_TTL = 100L;
    public static final String CACHE_SHOP_TYPE_LIST_KEY = "cache:shop:type:list:";

    public static final String LOCK_SHOP_KEY = "lock:shop:";
    public static final Long LOCK_SHOP_TTL = 10L;

    public static final String EMPTY_PLACEHOLDER = "_NULL_PLACEHOLDER_";

    public static final String SECKILL_STOCK_KEY = "seckill:stock:";
    public static final String SECKILL_ORDER_KEY = "seckill:order:";
    public static final String BLOG_LIKED_KEY = "blog:liked:";

    public static final String FOLLOWED_KEY = "followS:";
    public static final String FEED_KEY = "feed:";
    public static final String SHOP_GEO_KEY = "shop:geo:";
    public static final String USER_SIGN_KEY = "sign:";

}
