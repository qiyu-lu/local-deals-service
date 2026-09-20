package com.localdeals.trade.interceptor;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.localdeals.platform.dto.Result;
import com.localdeals.platform.exception.ApiErrorCodes;
import com.localdeals.trade.service.SeckillSoldOutRegistry;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * L1 in front of everything else, authentication included: once a voucher is flagged sold out
 * the purchase answers "库存不足" without a single network call. The answer is the same for every
 * caller, so skipping the token lookup reveals nothing.
 */
public class SeckillSoldOutInterceptor implements HandlerInterceptor {

    private final SeckillSoldOutRegistry registry;
    private final Counter rejected;
    private final String soldOutBody;

    public SeckillSoldOutInterceptor(SeckillSoldOutRegistry registry, MeterRegistry meterRegistry,
                                     ObjectMapper objectMapper) throws JsonProcessingException {
        this.registry = registry;
        this.soldOutBody = objectMapper.writeValueAsString(
                Result.fail(ApiErrorCodes.SECKILL_OUT_OF_STOCK, "库存不足"));
        this.rejected = Counter.builder("local_deals.seckill.requests")
                .tag("result", "rejected_sold_out_local").register(meterRegistry);
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        if (!"POST".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        @SuppressWarnings("unchecked")
        Map<String, String> variables = (Map<String, String>)
                request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        String id = variables == null ? null : variables.get("id");
        long voucherId;
        try {
            voucherId = Long.parseLong(id);
        } catch (NumberFormatException e) {
            return true;
        }
        if (!registry.rejectLocally(voucherId)) {
            return true;
        }
        rejected.increment();
        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(soldOutBody);
        return false;
    }
}
