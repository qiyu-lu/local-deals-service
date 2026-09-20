package com.localdeals.trade.interceptor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.localdeals.trade.service.SeckillSoldOutRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.HandlerMapping;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SeckillSoldOutInterceptorTest {

    private SeckillSoldOutRegistry registry;
    private SeckillSoldOutInterceptor interceptor;

    @BeforeEach
    void setUp() throws Exception {
        registry = mock(SeckillSoldOutRegistry.class);
        interceptor = new SeckillSoldOutInterceptor(registry, new SimpleMeterRegistry(), new ObjectMapper());
    }

    private static MockHttpServletRequest purchase(String method) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, "/voucher-order/seckill/17");
        request.setAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE, Map.of("id", "17"));
        return request;
    }

    @Test
    void aSoldOutVoucherIsAnsweredWithoutReachingAuthenticationOrTheService() throws Exception {
        when(registry.rejectLocally(17L)).thenReturn(true);
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean proceed = interceptor.preHandle(purchase("POST"), response, new Object());

        assertThat(proceed).isFalse();
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getContentAsString())
                .contains("\"success\":false").contains("\"code\":\"SECKILL_OUT_OF_STOCK\"");
    }

    @Test
    void otherwiseTheRequestContinues() throws Exception {
        when(registry.rejectLocally(17L)).thenReturn(false);

        assertThat(interceptor.preHandle(purchase("POST"), new MockHttpServletResponse(), new Object())).isTrue();
    }

    @Test
    void onlyThePurchaseIsShortCircuited() throws Exception {
        when(registry.rejectLocally(17L)).thenReturn(true);
        MockHttpServletRequest noVariables = new MockHttpServletRequest("POST", "/voucher-order/seckill/x");

        assertThat(interceptor.preHandle(purchase("GET"), new MockHttpServletResponse(), new Object())).isTrue();
        assertThat(interceptor.preHandle(noVariables, new MockHttpServletResponse(), new Object())).isTrue();
    }
}
