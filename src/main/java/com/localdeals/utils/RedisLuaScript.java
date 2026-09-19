package com.localdeals.utils;

import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

@Component
public class RedisLuaScript {
    // 解锁脚本,给 Redisson / 自定义分布式锁的解锁逻辑使用的
    public static final DefaultRedisScript<Long> UNLOCK_SCRIPT;

    static {
        // 解锁脚本
        UNLOCK_SCRIPT = new DefaultRedisScript<>();
        UNLOCK_SCRIPT.setLocation(
                new ClassPathResource("lua/unlock.lua")
        );
        UNLOCK_SCRIPT.setResultType(Long.class);
    }
}
