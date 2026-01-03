package com.hmdp.utils;

import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

public class SimpleRedisLock implements ILock {

    private StringRedisTemplate stringRedisTemplate;
    private final String KEY_PREFIX = "lock:";
    private String lockValue;

    SimpleRedisLock(){}
    public SimpleRedisLock(StringRedisTemplate stringRedisTemplate){
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @Override
    public boolean lock(String key, Long timeSecond){
        //如果value这里获取的话，会导致unlock无法保证只删掉自己的锁
//        String lockValue = UUID.randomUUID().toString();
         lockValue = UUID.randomUUID().toString();
         return Boolean.TRUE.equals(
                 stringRedisTemplate.opsForValue().setIfAbsent(KEY_PREFIX + key, lockValue, timeSecond, TimeUnit.SECONDS)
         );
    }
    @Override
    public void unlock(String key){
        String realKey = KEY_PREFIX + key;

        String value = stringRedisTemplate.opsForValue().get(realKey);
        if (lockValue.equals(value)) {
            stringRedisTemplate.delete(realKey);
        }
    }
}
