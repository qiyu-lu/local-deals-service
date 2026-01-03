package com.hmdp.utils;

import org.springframework.data.redis.core.StringRedisTemplate;

public interface ILock {
    public boolean lock(String key, Long timeSecond);
    void unlock(String key);
}
