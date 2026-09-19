package com.localdeals.trade.config;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.localdeals.trade.interceptor.SeckillSoldOutInterceptor;
import com.localdeals.trade.service.SeckillLocalRateLimiter;
import com.localdeals.trade.service.SeckillSoldOutRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

/** The two JVM-local layers of the admission funnel (L1 sold-out flag, L2 token bucket). */
@Configuration
public class SeckillFunnelConfig {

    @Bean
    public SeckillSoldOutRegistry seckillSoldOutRegistry(StringRedisTemplate redis, SeckillProperties properties,
                                                         ObjectProvider<RedisMessageListenerContainer> containers) {
        SeckillSoldOutRegistry registry = new SeckillSoldOutRegistry(redis,
                properties.getFunnel().getSoldOutProbeInterval(), System::currentTimeMillis);
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
    public SeckillLocalRateLimiter seckillLocalRateLimiter(SeckillProperties properties) {
        SeckillProperties.Funnel funnel = properties.getFunnel();
        return new SeckillLocalRateLimiter(funnel.getStockFactor(), funnel.getMinPermitsPerSecond(), System::nanoTime);
    }
}
