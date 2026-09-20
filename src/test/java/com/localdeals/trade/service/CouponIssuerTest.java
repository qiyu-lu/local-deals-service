package com.localdeals.trade.service;

import com.localdeals.marketing.entity.VoucherGrant;
import com.localdeals.trade.entity.TradeOrder;
import com.localdeals.trade.entity.UserCoupon;
import com.localdeals.trade.mapper.UserCouponMapper;
import com.localdeals.trade.mapper.VoucherMapper;
import com.localdeals.trade.mapper.VoucherMapper.VoucherSnapshot;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CouponIssuerTest {

    private static final VoucherSnapshot VOUCHER = new VoucherSnapshot(3L, 5L, 9L, 990L, 30);

    private final UserCouponMapper mapper = mock(UserCouponMapper.class);
    private final VoucherMapper vouchers = mock(VoucherMapper.class);
    private final VerifyCodeGenerator codes = mock(VerifyCodeGenerator.class);
    private final CouponIssuer issuer = new CouponIssuer(mapper, vouchers, codes);

    {
        when(vouchers.selectSnapshot(3L)).thenReturn(VOUCHER);
    }

    @Test
    void aPaidOrderGetsOnePurchaseCouponNamedAfterTheOrder() {
        when(codes.next()).thenReturn("CODE000000000001");
        when(mapper.insertFromVoucher("P42", 7L, VOUCHER, 9L, "PURCHASE", "42", "CODE000000000001")).thenReturn(1);
        UserCoupon stored = new UserCoupon();
        when(mapper.selectByCouponNo("P42")).thenReturn(stored);

        assertThat(issuer.issueForOrder(order(42L, 7L, 3L, 9L))).isSameAs(stored);
    }

    @Test
    void issuingAgainForTheSameOrderReturnsTheExistingCoupon() {
        when(codes.next()).thenReturn("CODE000000000002");
        when(mapper.insertFromVoucher(eq("P42"), anyLong(), any(), anyLong(), anyString(), anyString(), anyString()))
                .thenThrow(new DuplicateKeyException("uk_user_coupon_coupon_no"));
        UserCoupon existing = new UserCoupon();
        existing.setCouponNo("P42");
        when(mapper.selectByCouponNo("P42")).thenReturn(existing);

        assertThat(issuer.issueForOrder(order(42L, 7L, 3L, 9L))).isSameAs(existing);
        verify(mapper, times(1)).insertFromVoucher(eq("P42"), anyLong(), any(), anyLong(),
                anyString(), anyString(), anyString());
    }

    @Test
    void aVerifyCodeCollisionIsRetriedWithAFreshCode() {
        when(codes.next()).thenReturn("COLLIDING0000000", "FRESH00000000000");
        when(mapper.selectByCouponNo("G5")).thenReturn(null, new UserCoupon());
        when(mapper.insertFromVoucher("G5", 7L, VOUCHER, 9L, "ADMIN_GRANT", "5", "COLLIDING0000000"))
                .thenThrow(new DuplicateKeyException("uk_user_coupon_verify_code"));
        when(mapper.insertFromVoucher("G5", 7L, VOUCHER, 9L, "ADMIN_GRANT", "5", "FRESH00000000000")).thenReturn(1);

        assertThat(issuer.issueForGrant(grant(5L, "ADMIN_GRANT"))).isNotNull();
    }

    @Test
    void grantSourcesMapOntoCouponSources() {
        when(codes.next()).thenReturn("CODE000000000003");
        when(mapper.insertFromVoucher(anyString(), anyLong(), any(), anyLong(), anyString(), anyString(),
                anyString())).thenReturn(1);
        when(mapper.selectByCouponNo(anyString())).thenReturn(new UserCoupon());

        issuer.issueForGrant(grant(11L, "USER_CLAIM"));
        issuer.issueForGrant(grant(12L, "TASK_REWARD"));
        issuer.issueForGrant(grant(13L, "BATCH_GRANT"));

        verify(mapper).insertFromVoucher("G11", 7L, VOUCHER, 9L, "CLAIM", "11", "CODE000000000003");
        verify(mapper).insertFromVoucher("G12", 7L, VOUCHER, 9L, "TASK_REWARD", "12", "CODE000000000003");
        verify(mapper).insertFromVoucher("G13", 7L, VOUCHER, 9L, "BATCH_GRANT", "13", "CODE000000000003");
    }

    @Test
    void aMissingVoucherIsAnError() {
        when(codes.next()).thenReturn("CODE000000000004");

        assertThatThrownBy(() -> issuer.issueForOrder(order(43L, 7L, 3L, 9L)))
                .isInstanceOf(IllegalStateException.class);
    }

    private static TradeOrder order(long orderNo, long userId, long voucherId, long merchantId) {
        TradeOrder order = new TradeOrder();
        order.setOrderNo(orderNo);
        order.setUserId(userId);
        order.setVoucherId(voucherId);
        order.setMerchantId(merchantId);
        return order;
    }

    private static VoucherGrant grant(long id, String source) {
        VoucherGrant grant = new VoucherGrant();
        grant.setId(id);
        grant.setUserId(7L);
        grant.setVoucherId(3L);
        grant.setMerchantId(9L);
        grant.setSource(source);
        return grant;
    }
}
