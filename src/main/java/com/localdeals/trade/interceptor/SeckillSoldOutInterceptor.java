package com.localdeals.trade.interceptor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.localdeals.trade.service.SeckillSoldOutRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.web.servlet.HandlerInterceptor;

/** Stub for the red commit. */
public class SeckillSoldOutInterceptor implements HandlerInterceptor {

    public SeckillSoldOutInterceptor(SeckillSoldOutRegistry registry, MeterRegistry meterRegistry,
                                     ObjectMapper objectMapper) {
    }
}
