package com.localdeals.trade.config;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.localdeals.trade.interceptor.SeckillSoldOutInterceptor;
import com.localdeals.trade.service.SeckillBucketRouter;
import com.localdeals.trade.service.SeckillLocalRateLimiter;
import com.localdeals.trade.service.SeckillSoldOutRegistry;
import com.localdeals.trade.service.SeckillTokenService;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

/** The JVM-local parts of the admission funnel: L1 sold-out flag, L2 token bucket, purchase tokens. */
@Configuration
public class SeckillFunnelConfig {

    @Bean
    public SeckillSoldOutRegistry seckillSoldOutRegistry(StringRedisTemplate redis, SeckillProperties properties,
                                                         SeckillBucketRouter router,
                                                         ObjectProvider<RedisMessageListenerContainer> containers) {
        SeckillSoldOutRegistry registry = new SeckillSoldOutRegistry(redis,
                properties.getFunnel().getSoldOutProbeInterval(), System::currentTimeMillis, router);
        // Flags travel on the pub/sub container the WebSocket fan-out already runs.
        containers.ifAvailable(container ->
                container.addMessageListener(registry, new ChannelTopic(SeckillSoldOutRegistry.CHANNEL)));
        return registry;
    }

    @Bean
    public SeckillSoldOutInterceptor seckillSoldOutInterceptor(SeckillSoldOutRegistry registry,
                                                               MeterRegistry meterRegistry,
                                                               ObjectMapper objectMapper) throws JsonProcessingException {
        return new SeckillSoldOutInterceptor(registry, meterRegistry, objectMapper);
    }

    @Bean
    public SeckillTokenService seckillTokenService(StringRedisTemplate redis, SeckillProperties properties,
                                                   SeckillBucketRouter router) {
        return new SeckillTokenService(redis, properties.getToken(), () -> System.currentTimeMillis() / 1000,
                router);
    }

    @Bean
    public SeckillLocalRateLimiter seckillLocalRateLimiter(SeckillProperties properties,
                                                           SeckillBucketRouter router) {
        SeckillProperties.Funnel funnel = properties.getFunnel();
        return new SeckillLocalRateLimiter(funnel.getStockFactor(), funnel.getMinPermitsPerSecond(),
                System::nanoTime, router);
    }
}
