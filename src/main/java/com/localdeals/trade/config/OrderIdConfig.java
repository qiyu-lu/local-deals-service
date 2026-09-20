package com.localdeals.trade.config;

import com.localdeals.trade.utils.SnowflakeOrderIdGenerator;
import com.localdeals.trade.utils.WorkerIdLease;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

@Configuration
public class OrderIdConfig {

    @Bean(initMethod = "start", destroyMethod = "release")
    public WorkerIdLease workerIdLease(StringRedisTemplate redis, OrderProperties orderProperties) {
        return new WorkerIdLease(redis, orderProperties.getWorkerLeaseTtl(), System::currentTimeMillis);
    }

    @Bean
    public SnowflakeOrderIdGenerator orderIdGenerator(WorkerIdLease lease) {
        return new SnowflakeOrderIdGenerator(lease::workerId, System::currentTimeMillis);
    }
}
