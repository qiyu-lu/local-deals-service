package com.localdeals.service;

import com.localdeals.config.TrafficControlProperties;
import com.localdeals.exception.ApiErrorCodes;
import com.localdeals.exception.ApiStatusException;
import com.localdeals.observability.LocalDealsMetrics;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;

import static com.localdeals.observability.LocalDealsMetrics.TrafficReason.ACTIVITY;
import static com.localdeals.observability.LocalDealsMetrics.TrafficReason.IP;
import static com.localdeals.observability.LocalDealsMetrics.TrafficReason.NONE;
import static com.localdeals.observability.LocalDealsMetrics.TrafficReason.REDIS;
import static com.localdeals.observability.LocalDealsMetrics.TrafficReason.USER;
import static com.localdeals.observability.LocalDealsMetrics.TrafficResource.SECKILL;
import static com.localdeals.observability.LocalDealsMetrics.TrafficResult.ALLOWED;
import static com.localdeals.observability.LocalDealsMetrics.TrafficResult.REJECTED;
import static com.localdeals.observability.LocalDealsMetrics.TrafficResult.UNAVAILABLE;

/** Redis-server-time fixed-window guard for the new seckill HTTP entry point. */
@Slf4j
@Component
public class SeckillTrafficGuard {
    private static final DefaultRedisScript<Long> SCRIPT;

    static {
        SCRIPT = new DefaultRedisScript<>();
        SCRIPT.setLocation(new ClassPathResource("lua/seckill_traffic_guard.lua"));
        SCRIPT.setResultType(Long.class);
    }

    private final StringRedisTemplate redisTemplate;
    private final TrafficControlProperties properties;
    private final LocalDealsMetrics metrics;

    public SeckillTrafficGuard(StringRedisTemplate redisTemplate,
                               TrafficControlProperties properties,
                               LocalDealsMetrics metrics) {
        this.redisTemplate = redisTemplate;
        this.properties = properties;
        this.metrics = metrics;
    }

    public void check(Long voucherId, Long userId, String clientIp) {
        if (!properties.getSeckill().isEnabled()) {
            metrics.recordTraffic(SECKILL, ALLOWED, NONE);
            return;
        }
        if (voucherId == null || userId == null || clientIp == null) {
            throw new IllegalArgumentException("Seckill traffic identity is required");
        }

        TrafficControlProperties.Seckill limits = properties.getSeckill();
        Long result;
        try {
            String prefix = "traffic:seckill:{" + voucherId + "}:";
            result = redisTemplate.execute(SCRIPT, Arrays.asList(
                            prefix + "activity:",
                            prefix + "user:" + userId + ":",
                            prefix + "ip:" + sha256(clientIp) + ":"),
                    Long.toString(limits.getWindow().toMillis()),
                    Integer.toString(limits.getActivityLimit()),
                    Integer.toString(limits.getUserLimit()),
                    Integer.toString(limits.getIpLimit()));
        } catch (RuntimeException e) {
            metrics.recordTraffic(SECKILL, UNAVAILABLE, REDIS);
            log.warn("Seckill traffic guard is unavailable. voucherId={}", voucherId, e);
            throw unavailable();
        }

        if (result == null) {
            metrics.recordTraffic(SECKILL, UNAVAILABLE, REDIS);
            throw unavailable();
        }
        switch (result.intValue()) {
            case 0:
                metrics.recordTraffic(SECKILL, ALLOWED, NONE);
                return;
            case 1:
                reject(ACTIVITY);
                return;
            case 2:
                reject(USER);
                return;
            case 3:
                reject(IP);
                return;
            default:
                metrics.recordTraffic(SECKILL, UNAVAILABLE, REDIS);
                log.warn("Seckill traffic guard returned unknown result. voucherId={}, result={}",
                        voucherId, result);
                throw unavailable();
        }
    }

    private void reject(LocalDealsMetrics.TrafficReason reason) {
        metrics.recordTraffic(SECKILL, REJECTED, reason);
        throw new ApiStatusException(HttpStatus.TOO_MANY_REQUESTS,
                ApiErrorCodes.SECKILL_RATE_LIMITED, "请求过于频繁，请稍后重试");
    }

    private static ApiStatusException unavailable() {
        return new ApiStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                ApiErrorCodes.SECKILL_SUBMIT_UNAVAILABLE, "系统繁忙，请稍后重试");
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte current : digest) {
                hex.append(String.format("%02x", current & 0xff));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }
}
