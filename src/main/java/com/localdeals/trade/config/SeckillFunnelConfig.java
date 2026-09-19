package com.localdeals.trade.config;

import com.localdeals.trade.service.SeckillLocalRateLimiter;
import com.localdeals.trade.service.SeckillSoldOutRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

/** The two JVM-local layers of the admission funnel (L1 sold-out flag, L2 token bucket). */
@Configuration
public class SeckillFunnelConfig {

    @Bean
    public SeckillSoldOutRegistry seckillSoldOutRegistry(StringRedisTemplate redis, SeckillProperties properties) {
        return new SeckillSoldOutRegistry(redis, properties.getFunnel().getSoldOutProbeInterval(),
                System::currentTimeMillis);
    }

    @Bean
    public SeckillLocalRateLimiter seckillLocalRateLimiter(SeckillProperties properties) {
        SeckillProperties.Funnel funnel = properties.getFunnel();
        return new SeckillLocalRateLimiter(funnel.getStockFactor(), funnel.getMinPermitsPerSecond(), System::nanoTime);
    }
}
