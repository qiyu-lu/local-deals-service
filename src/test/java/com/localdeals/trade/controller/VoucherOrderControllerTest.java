package com.localdeals.trade.controller;

import com.localdeals.platform.dto.Result;
import com.localdeals.platform.dto.UserDTO;
import com.localdeals.platform.exception.ApiErrorCodes;
import com.localdeals.platform.exception.ApiStatusException;
import com.localdeals.platform.service.TrustedClientIpResolver;
import com.localdeals.platform.utils.UserHolder;
import com.localdeals.trade.service.IVoucherOrderService;
import com.localdeals.trade.service.SeckillTokenService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class VoucherOrderControllerTest {

    private VoucherOrderController controller;
    private IVoucherOrderService service;
    private TrustedClientIpResolver resolver;
    private SeckillTokenService tokens;
    private final MockHttpServletRequest request = new MockHttpServletRequest();

    @BeforeEach
    void setUp() {
        controller = new VoucherOrderController();
        service = mock(IVoucherOrderService.class);
        resolver = mock(TrustedClientIpResolver.class);
        tokens = mock(SeckillTokenService.class);
        when(resolver.resolve(request)).thenReturn("203.0.113.9");
        when(tokens.isRequired()).thenReturn(true);
        ReflectionTestUtils.setField(controller, "voucherOrderService", service);
        ReflectionTestUtils.setField(controller, "clientIpResolver", resolver);
        ReflectionTestUtils.setField(controller, "seckillTokenService", tokens);
        UserDTO user = new UserDTO();
        user.setId(23L);
        UserHolder.saveUser(user);
    }

    @AfterEach
    void tearDown() {
        UserHolder.removeUser();
    }

    @Test
    void aValidTokenPassesTheSharedTrustedClientIpToTheService() {
        Result expected = Result.ok("9007199254740993");
        when(tokens.verify(23L, 17L, "t")).thenReturn(true);
        when(service.seckillVoucher(17L, "203.0.113.9")).thenReturn(expected);

        assertThat(controller.seckillVoucher(17L, "t", request)).isSameAs(expected);

        verify(service).seckillVoucher(17L, "203.0.113.9");
    }

    @Test
    void aMissingOrForeignTokenIsRejectedBeforeAnyFunnelLayer() {
        when(tokens.verify(23L, 17L, null)).thenReturn(false);

        assertThatThrownBy(() -> controller.seckillVoucher(17L, null, request))
                .isInstanceOfSatisfying(ApiStatusException.class, error -> {
                    assertThat(error.getStatus().value()).isEqualTo(403);
                    assertThat(error.getCode()).isEqualTo(ApiErrorCodes.SECKILL_TOKEN_INVALID);
                });
        verifyNoInteractions(service);
    }

    @Test
    void theTokenCheckCanBeSwitchedOff() {
        when(tokens.isRequired()).thenReturn(false);

        controller.seckillVoucher(17L, null, request);

        verify(service).seckillVoucher(17L, "203.0.113.9");
    }

    @Test
    void theTokenIsIssuedForTheLoggedInUser() {
        Result issued = Result.ok("1790000600.sig");
        when(tokens.issue(23L, 17L)).thenReturn(issued);

        assertThat(controller.seckillToken(17L)).isSameAs(issued);
    }
}
