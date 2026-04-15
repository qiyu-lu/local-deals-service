package com.hmdp.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RedissonConfig {

    @Bean
    public RedissonClient redissonClient(){
        Config config = new Config();
        config.useSingleServer()
                .setAddress("redis://localhost:6379")
                .setPassword("redis123")
                .setKeepAlive(true);
//                // ↓↓↓ 关键修改：减小主连接池大小 ↓↓↓
//                .setConnectionPoolSize(10); // 默认是64，我们把它降到10;
        return Redisson.create(config);
    }
}
