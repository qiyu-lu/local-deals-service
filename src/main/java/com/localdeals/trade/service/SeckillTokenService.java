package com.localdeals.trade.service;

import com.localdeals.platform.dto.Result;
import com.localdeals.platform.exception.ApiErrorCodes;
import com.localdeals.trade.config.SeckillProperties;
import org.springframework.data.redis.core.StringRedisTemplate;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.function.LongSupplier;

/**
 * Short-lived seckill tokens bound to one user and one voucher, handed out only while the
 * activity is open. The purchase endpoint verifies them with an HMAC and no network IO, so a
 * script that fires before the start, or replays another user's request, is stopped before any
 * funnel layer spends work on it.
 *
 * <p>Format: {@code <expiryEpochSeconds>.<base64url(HMAC-SHA256(secret, "userId:voucherId:expiry"))>}.</p>
 */
public class SeckillTokenService {

    private final StringRedisTemplate redis;
    private final SeckillProperties.Token config;
    private final LongSupplier epochSeconds;
    private final SeckillBucketRouter router;
    private final ThreadLocal<Mac> macs;

    public SeckillTokenService(StringRedisTemplate redis, SeckillProperties.Token config, LongSupplier epochSeconds,
                               SeckillBucketRouter router) {
        this.router = router;
        this.redis = redis;
        this.config = config;
        this.epochSeconds = epochSeconds;
        byte[] key = config.getSecret().getBytes(StandardCharsets.UTF_8);
        this.macs = ThreadLocal.withInitial(() -> {
            try {
                Mac mac = Mac.getInstance("HmacSHA256");
                mac.init(new SecretKeySpec(key, "HmacSHA256"));
                return mac;
            } catch (GeneralSecurityException e) {
                throw new IllegalStateException("HmacSHA256 is unavailable", e);
            }
        });
    }

    public boolean isRequired() {
        return config.isRequired();
    }

    /** Issues a token only while the activity is ACTIVE and inside its window. */
    public Result issue(long userId, long voucherId) {
        List<Object> meta = redis.opsForHash().multiGet(
                router.metaKey(voucherId, router.bucketOfUser(userId)),
                Arrays.asList("status", "beginAt", "endAt"));
        Long beginAt = meta == null ? null : parse(meta.get(1));
        Long endAt = meta == null ? null : parse(meta.get(2));
        if (meta == null || meta.get(0) == null || beginAt == null || endAt == null) {
            return Result.fail(ApiErrorCodes.SECKILL_STATE_UNAVAILABLE, "活动正在初始化，请稍后重试");
        }
        long now = epochSeconds.getAsLong();
        if (now < beginAt) {
            return Result.fail(ApiErrorCodes.SECKILL_NOT_STARTED, "秒杀活动尚未开始");
        }
        if (!"ACTIVE".equals(meta.get(0).toString()) || now > endAt) {
            return Result.fail(ApiErrorCodes.SECKILL_ENDED, "秒杀活动已结束或暂停");
        }
        long expiry = now + config.getTtl().getSeconds();
        return Result.ok(expiry + "." + sign(userId, voucherId, expiry));
    }

    public boolean verify(long userId, long voucherId, String token) {
        if (token == null) {
            return false;
        }
        int dot = token.indexOf('.');
        if (dot <= 0 || dot == token.length() - 1) {
            return false;
        }
        long expiry;
        try {
            expiry = Long.parseLong(token.substring(0, dot));
        } catch (NumberFormatException e) {
            return false;
        }
        if (expiry < epochSeconds.getAsLong()) {
            return false;
        }
        byte[] expected = sign(userId, voucherId, expiry).getBytes(StandardCharsets.US_ASCII);
        byte[] actual = token.substring(dot + 1).getBytes(StandardCharsets.US_ASCII);
        return MessageDigest.isEqual(expected, actual);
    }

    private String sign(long userId, long voucherId, long expiry) {
        byte[] digest = macs.get().doFinal(
                (userId + ":" + voucherId + ":" + expiry).getBytes(StandardCharsets.UTF_8));
        return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
    }

    private static Long parse(Object value) {
        try {
            return value == null ? null : Long.valueOf(value.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
