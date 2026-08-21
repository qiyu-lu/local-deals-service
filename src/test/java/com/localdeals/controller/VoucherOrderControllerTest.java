package com.localdeals.controller;

import com.localdeals.dto.Result;
import com.localdeals.service.IVoucherOrderService;
import com.localdeals.service.TrustedClientIpResolver;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class VoucherOrderControllerTest {
    @Test
    void explicitlyPassesTheSharedTrustedClientIpToTheService() {
        VoucherOrderController controller = new VoucherOrderController();
        IVoucherOrderService service = mock(IVoucherOrderService.class);
        TrustedClientIpResolver resolver = mock(TrustedClientIpResolver.class);
        MockHttpServletRequest request = new MockHttpServletRequest();
        Result expected = Result.ok("9007199254740993");
        when(resolver.resolve(request)).thenReturn("203.0.113.9");
        when(service.seckillVoucher(17L, "203.0.113.9")).thenReturn(expected);
        ReflectionTestUtils.setField(controller, "voucherOrderService", service);
        ReflectionTestUtils.setField(controller, "clientIpResolver", resolver);

        assertThat(controller.seckillVoucher(17L, request)).isSameAs(expected);

        verify(service).seckillVoucher(17L, "203.0.113.9");
    }
}
