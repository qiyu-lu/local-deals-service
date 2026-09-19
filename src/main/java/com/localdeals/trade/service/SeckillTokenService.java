package com.localdeals.trade.service;

import com.localdeals.platform.dto.Result;
import com.localdeals.trade.config.SeckillProperties;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.function.LongSupplier;

/** Stub for the red commit. */
public class SeckillTokenService {

    public SeckillTokenService(StringRedisTemplate redis, SeckillProperties.Token config, LongSupplier epochSeconds) {
    }

    public boolean isRequired() {
        throw new UnsupportedOperationException("not implemented");
    }

    public Result issue(long userId, long voucherId) {
        throw new UnsupportedOperationException("not implemented");
    }

    public boolean verify(long userId, long voucherId, String token) {
        throw new UnsupportedOperationException("not implemented");
    }
}
