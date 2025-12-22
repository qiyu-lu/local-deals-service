package com.hmdp.utils;

import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

@Component
public class RedisIdWorker {
    //使用某个时间作为时间戳的起点
    private static final long BEGIN_TIMESTAMP = 1766432420L;
    //序列占用的位数
    private static final int COUNT_BITS = 32;
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    public long nextId(String keyPrefix){
        //获取相对时间戳
        long nowSecond = LocalDateTime.now().toEpochSecond(ZoneOffset.UTC);
        long timestamp = nowSecond - BEGIN_TIMESTAMP;

        //拼接redisKey
        String date = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy:MM:dd"));
        String redisKey = "icr" + keyPrefix + ":" + date;

        //获取序列号
        long count = stringRedisTemplate.opsForValue().increment(redisKey);

        //拼接全局ID
        return (timestamp << COUNT_BITS) | count;
    }

//    public static void main(String[] args) {
//        Long now = LocalDateTime.now().toEpochSecond(ZoneOffset.UTC);
//        System.out.println("当前时间：" + now);
//    }
}
