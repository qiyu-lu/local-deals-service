package com.localdeals.config;

import com.localdeals.service.BusinessDateProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.ZoneId;

@Configuration
public class BusinessDateConfiguration {
    @Bean
    @ConditionalOnMissingBean(Clock.class)
    public Clock businessClock() {
        return Clock.systemUTC();
    }

    @Bean
    @ConditionalOnMissingBean(BusinessDateProvider.class)
    public BusinessDateProvider businessDateProvider(Clock businessClock,
            @Value("${local-deals.business-time-zone:Asia/Shanghai}") String businessTimeZone) {
        return new BusinessDateProvider(businessClock, ZoneId.of(businessTimeZone));
    }
}
