package com.localdeals.trade.service;

import com.localdeals.marketing.entity.VoucherGrant;
import com.localdeals.merchant.dto.AdminPrincipal;
import com.localdeals.platform.exception.ApiErrorCodes;
import com.localdeals.platform.exception.ApiStatusException;
import com.localdeals.trade.entity.OrderEvent;
import com.localdeals.trade.entity.TradeOrder;
import com.localdeals.trade.entity.UserCoupon;
import com.localdeals.trade.mapper.TradeOrderMapper;
import com.localdeals.trade.mq.SeckillOrderMessage;
import com.localdeals.trade.testsupport.TradeFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Coupon assets: issuance for purchases and grants, verification at the shop, expiry. */
@SpringBootTest
@ActiveProfiles("test")
class CouponVerifyIT {

    private static final long BASE = 9_204_000L;
    private static final long USER = 9_204_001L;

    @Autowired
    private IVoucherOrderService orderService;
    @Autowired
    private OrderStateMachine stateMachine;
    @Autowired
    private TradeOrderMapper orderMapper;
    @Autowired
    private CouponIssuer issuer;
    @Autowired
    private CouponService couponService;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private TransactionTemplate tx;

    private TradeFixture fixture;

    @BeforeEach
    void setUp() {
        fixture = new TradeFixture(jdbc, BASE).create(10);
        jdbc.update("UPDATE tb_voucher SET valid_days = 7 WHERE id = ?", fixture.voucherId);
    }

    @AfterEach
    void tearDown() {
        fixture.delete();
    }

    @Test
    void aPaidOrderHasExactlyOnePurchaseCouponValidForTheVouchersDays() {
        UserCoupon coupon = paidOrderCoupon(order(1));
        UserCoupon again = tx.execute(status -> issuer.issueForOrder(orderMapper.selectById(order(1))));

        assertThat(again.getVerifyCode()).isEqualTo(coupon.getVerifyCode());
        assertThat(coupon.getCouponNo()).isEqualTo("P" + (order(1)));
        assertThat(coupon.getMerchantId()).isEqualTo(fixture.merchantId);
        assertThat(coupon.getShopId()).isEqualTo(fixture.shopId);
        assertThat(coupon.getStatus().name()).isEqualTo("AVAILABLE");
        assertThat(validDays(coupon.getCouponNo())).isEqualTo(7);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM user_coupon WHERE source_ref = ?", Integer.class,
                Long.toString(order(1)))).isEqualTo(1);
    }

    @Test
    void aGrantIssuesAGrantCoupon() {
        VoucherGrant grant = new VoucherGrant();
        grant.setId(BASE + 50);
        grant.setUserId(USER);
        grant.setVoucherId(fixture.voucherId);
        grant.setMerchantId(fixture.merchantId);
        grant.setSource("TASK_REWARD");

        UserCoupon coupon = tx.execute(status -> issuer.issueForGrant(grant));

        assertThat(coupon.getCouponNo()).isEqualTo("G" + (BASE + 50));
        assertThat(coupon.getSource().name()).isEqualTo("TASK_REWARD");
        assertThat(validDays(coupon.getCouponNo())).isEqualTo(7);
    }

    @Test
    void verifyingConsumesTheCouponAndMovesThePurchaseOrderToUsed() {
        UserCoupon coupon = paidOrderCoupon(order(2));

        UserCoupon used = couponService.verify(coupon.getVerifyCode(), merchant(fixture.merchantId));

        assertThat(used.getStatus().name()).isEqualTo("USED");
        assertThat(used.getVerifiedBy()).isEqualTo(BASE + 900);
        assertThat(fixture.orderStatus(order(2))).isEqualTo("USED");
        assertRejected(() -> couponService.verify(coupon.getVerifyCode(), merchant(fixture.merchantId)),
                ApiErrorCodes.COUPON_ALREADY_USED);
    }

    @Test
    void concurrentVerificationsOfOneCodeLetExactlyOneWin() throws Exception {
        UserCoupon coupon = paidOrderCoupon(order(3));
        ExecutorService pool = Executors.newFixedThreadPool(10);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> results = new ArrayList<>();
        try {
            for (int i = 0; i < 10; i++) {
                results.add(pool.submit(() -> {
                    start.await();
                    try {
                        couponService.verify(coupon.getVerifyCode(), merchant(fixture.merchantId));
                        return true;
                    } catch (ApiStatusException rejected) {
                        return false;
                    }
                }));
            }
            start.countDown();
            int winners = 0;
            for (Future<Boolean> result : results) {
                winners += result.get(30, TimeUnit.SECONDS) ? 1 : 0;
            }
            assertThat(winners).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM order_state_log WHERE order_no = ? AND event = 'VERIFY'",
                    Integer.class, order(3))).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void anotherMerchantsCodeLooksExactlyLikeAnUnknownCode() {
        UserCoupon coupon = paidOrderCoupon(order(4));

        assertRejected(() -> couponService.verify(coupon.getVerifyCode(), merchant(fixture.merchantId + 1)),
                ApiErrorCodes.COUPON_INVALID);
        assertRejected(() -> couponService.verify("0000000000000000", merchant(fixture.merchantId)),
                ApiErrorCodes.COUPON_INVALID);
        assertThat(fixture.orderStatus(order(4))).isEqualTo("PAID");
    }

    @Test
    void anExpiredCouponIsRejectedAndTheExpiryJobMarksIt() {
        UserCoupon coupon = paidOrderCoupon(order(5));
        jdbc.update("UPDATE user_coupon SET valid_to = NOW(3) - INTERVAL 1 SECOND WHERE id = ?", coupon.getId());

        assertRejected(() -> couponService.verify(coupon.getVerifyCode(), merchant(fixture.merchantId)),
                ApiErrorCodes.COUPON_EXPIRED);
        assertThat(fixture.orderStatus(order(5))).isEqualTo("PAID");

        assertThat(couponService.expireDue(1_000)).isGreaterThanOrEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM user_coupon WHERE id = ?", String.class, coupon.getId()))
                .isEqualTo("EXPIRED");
    }

    @Test
    void theWalletListsTheUsersCoupons() {
        paidOrderCoupon(order(6));

        assertThat(couponService.listMine(USER)).extracting(UserCoupon::getCouponNo).contains("P" + (order(6)));
    }

    /** A routable order number of USER; see TradeFixture.orderNo. */
    private static long order(long seed) {
        return TradeFixture.orderNo(BASE + seed, USER);
    }

    private UserCoupon paidOrderCoupon(long orderNo) {
        orderService.createPendingOrder(new SeckillOrderMessage(fixture.voucherId, USER, orderNo));
        return tx.execute(status -> {
            assertThat(stateMachine.fire(orderNo, OrderEvent.PAY, "TEST")).isTrue();
            TradeOrder order = orderMapper.selectById(orderNo);
            return issuer.issueForOrder(order);
        });
    }

    private int validDays(String couponNo) {
        return jdbc.queryForObject("SELECT TIMESTAMPDIFF(DAY, valid_from, valid_to) FROM user_coupon " +
                "WHERE coupon_no = ?", Integer.class, couponNo);
    }

    private static AdminPrincipal merchant(long merchantId) {
        AdminPrincipal principal = new AdminPrincipal();
        principal.setAccountId(BASE + 900);
        principal.setMerchantId(merchantId);
        principal.setScopeType(AdminPrincipal.SCOPE_MERCHANT);
        return principal;
    }

    private static void assertRejected(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, String code) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ApiStatusException.class,
                error -> assertThat(error.getCode()).isEqualTo(code));
    }
}
