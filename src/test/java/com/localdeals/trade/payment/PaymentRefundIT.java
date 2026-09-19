package com.localdeals.trade.payment;

import com.localdeals.merchant.dto.AdminPrincipal;
import com.localdeals.platform.exception.ApiErrorCodes;
import com.localdeals.platform.exception.ApiStatusException;
import com.localdeals.trade.mq.SeckillOrderMessage;
import com.localdeals.trade.service.CouponService;
import com.localdeals.trade.service.IVoucherOrderService;
import com.localdeals.trade.service.OrderCloseService;
import com.localdeals.trade.service.PaymentCallbackService;
import com.localdeals.trade.service.PaymentCallbackService.PaidOutcome;
import com.localdeals.trade.service.PaymentService;
import com.localdeals.trade.service.RefundService;
import com.localdeals.trade.testsupport.TradeFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;

/**
 * Payment and refund against real MySQL and Redis, with the channel replaced by a Mockito mock
 * and its callbacks built and signed here. Covers plan races 1, 2 and 4.
 */
@SpringBootTest
@ActiveProfiles("test")
class PaymentRefundIT {

    private static final long BASE = 9_205_000L;
    private static final long USER = 9_205_001L;
    private static final int STOCK = 100;
    private static final long HEAD_START_MS = 20;

    @Autowired
    private IVoucherOrderService orderService;
    @Autowired
    private PaymentService paymentService;
    @Autowired
    private PaymentCallbackService callbackService;
    @Autowired
    private RefundService refundService;
    @Autowired
    private OrderCloseService closeService;
    @Autowired
    private CouponService couponService;
    @Autowired
    private PaymentSigner signer;
    @Autowired
    private JdbcTemplate jdbc;
    @MockitoBean
    private PaymentChannel channel;

    private TradeFixture fixture;

    @BeforeEach
    void setUp() {
        fixture = new TradeFixture(jdbc, BASE).create(STOCK);
    }

    @AfterEach
    void tearDown() {
        fixture.delete();
    }

    @Test
    void prepayIsReusedForTheSameOrderAndRefusedForOthersOrdersAndExpiredOrders() {
        long orderNo = order(1);

        PaymentService.Prepay first = paymentService.prepay(USER, orderNo);
        PaymentService.Prepay again = paymentService.prepay(USER, orderNo);

        assertThat(again.getPayNo()).isEqualTo(first.getPayNo());
        assertThat(first.getAmount()).isEqualTo(TradeFixture.PAY_VALUE);
        verify(channel, atLeastOnce()).createPayment(first.getPayNo(), TradeFixture.PAY_VALUE);
        assertRejected(() -> paymentService.prepay(USER + 1, orderNo), ApiErrorCodes.ORDER_NOT_FOUND);
        fixture.expire(orderNo);
        assertRejected(() -> paymentService.prepay(USER, orderNo), ApiErrorCodes.ORDER_NOT_PAYABLE);
    }

    @Test
    void aForgedCallbackChangesNothing() {
        long orderNo = order(2);
        String payNo = paymentService.prepay(USER, orderNo).getPayNo();
        PaymentNotification forged = new PaymentNotification(payNo, "TXN-FORGED", TradeFixture.PAY_VALUE, "00");

        assertRejected(() -> callbackService.onPaid(forged), ApiErrorCodes.PAYMENT_SIGNATURE_INVALID);
        assertThat(fixture.orderStatus(orderNo)).isEqualTo("PENDING_PAY");
    }

    @Test
    void aCallbackForAnUnknownPaymentIsRefusedSoTheChannelRetries() {
        assertRejected(() -> callbackService.onPaid(paid("P-unknown", "TXN-X")), ApiErrorCodes.PAYMENT_NOT_FOUND);
    }

    /** Plan race 2. */
    @Test
    void aCallbackDeliveredTenTimesAtOncePaysTheOrderOnce() throws Exception {
        long orderNo = order(3);
        String payNo = paymentService.prepay(USER, orderNo).getPayNo();
        PaymentNotification notification = paid(payNo, "TXN-" + orderNo);

        List<PaidOutcome> outcomes = concurrently(10, () -> callbackService.onPaid(notification));

        assertThat(outcomes).containsOnlyOnce(PaidOutcome.PAID);
        assertThat(outcomes).filteredOn(o -> o != PaidOutcome.PAID).containsOnly(PaidOutcome.DUPLICATE);
        assertThat(fixture.orderStatus(orderNo)).isEqualTo("PAID");
        assertThat(count("SELECT COUNT(*) FROM payment_record WHERE order_no = ? AND status = 'SUCCESS'", orderNo)).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM user_coupon WHERE source_ref = ?", Long.toString(orderNo))).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM order_state_log WHERE order_no = ? AND event = 'PAY'", orderNo)).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM refund_record WHERE order_no = ?", orderNo)).isZero();
    }

    @Test
    void anAmountMismatchIsRecordedAsAbnormalAndNeverApplied() {
        long orderNo = order(4);
        String payNo = paymentService.prepay(USER, orderNo).getPayNo();
        PaymentNotification wrongAmount = signed(new PaymentNotification(payNo, "TXN-" + orderNo, 1L, null));

        assertThat(callbackService.onPaid(wrongAmount)).isEqualTo(PaidOutcome.ABNORMAL);
        assertThat(callbackService.onPaid(wrongAmount)).isEqualTo(PaidOutcome.DUPLICATE);
        assertThat(fixture.orderStatus(orderNo)).isEqualTo("PENDING_PAY");
    }

    /** Plan race 1, deterministic half: the close wins, the money arrives late and goes back. */
    @Test
    void aPaymentArrivingAfterTheCloseIsRefundedAutomatically() {
        long orderNo = order(5);
        String payNo = paymentService.prepay(USER, orderNo).getPayNo();
        fixture.expire(orderNo);
        assertThat(closeService.closeIfExpired(orderNo, "SYSTEM")).isEqualTo(OrderCloseService.Outcome.CLOSED);

        assertThat(callbackService.onPaid(paid(payNo, "TXN-" + orderNo))).isEqualTo(PaidOutcome.AUTO_REFUND);

        assertThat(fixture.orderStatus(orderNo)).isEqualTo("CLOSED");
        assertThat(count("SELECT COUNT(*) FROM user_coupon WHERE source_ref = ?", Long.toString(orderNo))).isZero();
        String refundNo = jdbc.queryForObject("SELECT refund_no FROM refund_record WHERE order_no = ? AND type = 'AUTO'",
                String.class, orderNo);
        assertThat(refundNo).isEqualTo("RA" + payNo);
        verify(channel).refund(refundNo, payNo, TradeFixture.PAY_VALUE);

        assertThat(callbackService.onRefunded(refunded(refundNo))).isEqualTo(PaymentCallbackService.RefundOutcome.REFUNDED);
        assertThat(jdbc.queryForObject("SELECT status FROM refund_record WHERE refund_no = ?", String.class, refundNo))
                .isEqualTo("SUCCESS");
        assertThat(fixture.orderStatus(orderNo)).isEqualTo("CLOSED");
        assertThat(fixture.dbStock()).isEqualTo(STOCK);
    }

    /** Plan race 1: callback and close at the same instant, many times; exactly one side wins. */
    @Test
    void paymentAndCloseRacingLeaveExactlyOneConsistentOutcome() throws Exception {
        int paidWins = 0;
        int closeWins = 0;
        for (int i = 0; i < 20; i++) {
            long orderNo = order(100 + i);
            String payNo = paymentService.prepay(USER + 100 + i, orderNo).getPayNo();
            fixture.expire(orderNo);
            PaymentNotification notification = paid(payNo, "TXN-" + orderNo);
            int stockBefore = fixture.dbStock();

            // Rounds 0-9 start both at once; 10-14 give the callback a head start, 15-19 the close,
            // so both interleavings are exercised whatever the natural winner is on this machine.
            long payDelay = i >= 15 ? HEAD_START_MS : 0;
            long closeDelay = i >= 10 && i < 15 ? HEAD_START_MS : 0;
            List<Object> outcomes = concurrently(2, List.of(
                    () -> after(payDelay, () -> callbackService.onPaid(notification)),
                    () -> after(closeDelay, () -> closeService.closeIfExpired(orderNo, "SYSTEM"))));

            String status = fixture.orderStatus(orderNo);
            int coupons = count("SELECT COUNT(*) FROM user_coupon WHERE source_ref = ?", Long.toString(orderNo));
            int autoRefunds = count("SELECT COUNT(*) FROM refund_record WHERE order_no = ? AND type = 'AUTO'", orderNo);
            if ("PAID".equals(status)) {
                paidWins++;
                assertThat(outcomes).containsExactly(PaidOutcome.PAID, OrderCloseService.Outcome.NOT_PENDING);
                assertThat(coupons).isEqualTo(1);
                assertThat(autoRefunds).isZero();
                assertThat(fixture.dbStock()).isEqualTo(stockBefore);
            } else {
                closeWins++;
                assertThat(status).isEqualTo("CLOSED");
                assertThat(outcomes).containsExactly(PaidOutcome.AUTO_REFUND, OrderCloseService.Outcome.CLOSED);
                assertThat(coupons).isZero();
                assertThat(autoRefunds).isEqualTo(1);
                assertThat(fixture.dbStock()).isEqualTo(stockBefore + 1);
            }
        }
        assertThat(paidWins + closeWins).isEqualTo(20);
        assertThat(paidWins).isPositive();
        assertThat(closeWins).isPositive();
        System.out.printf("race 1 (payment vs close): payment won %d, close won %d%n", paidWins, closeWins);
    }

    /** Plan race 4a: refund while a refund is in flight. */
    @Test
    void concurrentRefundRequestsCreateOneRefund() throws Exception {
        long orderNo = paidOrder(6);

        List<Object> results = concurrently(10, () -> {
            try {
                return refundService.apply(USER, orderNo).getRefundNo();
            } catch (ApiStatusException e) {
                return e.getCode();
            }
        });

        assertThat(results).containsOnlyOnce("RU" + orderNo);
        assertThat(results).filteredOn(r -> !("RU" + orderNo).equals(r)).containsOnly(ApiErrorCodes.REFUND_IN_PROGRESS);
        assertThat(fixture.orderStatus(orderNo)).isEqualTo("REFUNDING");
        assertThat(couponStatus(orderNo)).isEqualTo("FROZEN");
        assertThat(count("SELECT COUNT(*) FROM refund_record WHERE order_no = ?", orderNo)).isEqualTo(1);
        verify(channel).refund(eq("RU" + orderNo), anyString(), eq(TradeFixture.PAY_VALUE));
        assertRejected(() -> couponService.verify(couponCode(orderNo), merchant()), ApiErrorCodes.COUPON_NOT_USABLE);
    }

    /** Plan race 4b. */
    @Test
    void aVerifiedOrderCannotBeRefunded() {
        long orderNo = paidOrder(7);
        couponService.verify(couponCode(orderNo), merchant());

        assertRejected(() -> refundService.apply(USER, orderNo), ApiErrorCodes.ORDER_ALREADY_USED);
        assertThat(count("SELECT COUNT(*) FROM refund_record WHERE order_no = ?", orderNo)).isZero();
    }

    /** Plan race 4, the dangerous interleaving: verification at the shop vs a refund request. */
    @Test
    void verifyAndRefundRacingLetExactlyOneWin() throws Exception {
        int verifyWins = 0;
        for (int i = 0; i < 10; i++) {
            long orderNo = paidOrder(200 + i);
            long buyer = USER + 200 + i;
            String code = couponCode(orderNo);

            long verifyDelay = i >= 7 ? HEAD_START_MS : 0;
            long refundDelay = i >= 4 && i < 7 ? HEAD_START_MS : 0;
            List<Object> results = concurrently(2, List.of(
                    () -> after(verifyDelay, () -> attempt(() -> couponService.verify(code, merchant()))),
                    () -> after(refundDelay, () -> attempt(() -> refundService.apply(buyer, orderNo)))));

            String status = fixture.orderStatus(orderNo);
            if ("USED".equals(status)) {
                verifyWins++;
                assertThat(results.get(1)).isEqualTo(ApiErrorCodes.ORDER_ALREADY_USED);
                assertThat(couponStatus(orderNo)).isEqualTo("USED");
                assertThat(count("SELECT COUNT(*) FROM refund_record WHERE order_no = ?", orderNo)).isZero();
            } else {
                assertThat(status).isEqualTo("REFUNDING");
                assertThat(results.get(0)).isEqualTo(ApiErrorCodes.COUPON_NOT_USABLE);
                assertThat(couponStatus(orderNo)).isEqualTo("FROZEN");
            }
        }
        assertThat(verifyWins).isPositive();
        assertThat(verifyWins).isLessThan(10);
        System.out.printf("race 4 (verify vs refund): verify won %d, refund won %d%n", verifyWins, 10 - verifyWins);
    }

    @Test
    void theRefundCallbackFinishesTheRefundReturnsStockOnceAndAllowsBuyingAgain() {
        long orderNo = paidOrder(8);
        int stockAfterPurchase = fixture.dbStock();
        String refundNo = refundService.apply(USER, orderNo).getRefundNo();

        assertThat(callbackService.onRefunded(refunded(refundNo))).isEqualTo(PaymentCallbackService.RefundOutcome.REFUNDED);
        assertThat(callbackService.onRefunded(refunded(refundNo))).isEqualTo(PaymentCallbackService.RefundOutcome.DUPLICATE);

        assertThat(fixture.orderStatus(orderNo)).isEqualTo("REFUNDED");
        assertThat(couponStatus(orderNo)).isEqualTo("REFUNDED");
        assertThat(fixture.dbStock()).isEqualTo(stockAfterPurchase + 1);
        orderService.createPendingOrder(new SeckillOrderMessage(fixture.voucherId, USER, BASE + 9));
        assertThat(fixture.orderStatus(BASE + 9)).isEqualTo("PENDING_PAY");
    }

    @Test
    void unconfirmedRefundsAreRequestedAgain() {
        long orderNo = paidOrder(10);
        String refundNo = refundService.apply(USER, orderNo).getRefundNo();
        jdbc.update("UPDATE refund_record SET update_time = NOW(3) - INTERVAL 10 MINUTE WHERE refund_no = ?", refundNo);

        assertThat(refundService.retryStale(100)).isGreaterThanOrEqualTo(1);

        verify(channel, org.mockito.Mockito.times(2)).refund(eq(refundNo), anyString(), anyLong());
        assertThat(count("SELECT request_attempts FROM refund_record WHERE refund_no = ?", refundNo)).isEqualTo(2);
    }

    private long order(int offset) {
        long orderNo = BASE + offset;
        orderService.createPendingOrder(new SeckillOrderMessage(fixture.voucherId, USER + (offset >= 100 ? offset : 0), orderNo));
        return orderNo;
    }

    private long paidOrder(int offset) {
        long orderNo = order(offset);
        long userId = USER + (offset >= 100 ? offset : 0);
        String payNo = paymentService.prepay(userId, orderNo).getPayNo();
        assertThat(callbackService.onPaid(paid(payNo, "TXN-" + orderNo))).isEqualTo(PaidOutcome.PAID);
        return orderNo;
    }

    private PaymentNotification paid(String payNo, String txn) {
        return signed(new PaymentNotification(payNo, txn, TradeFixture.PAY_VALUE, null));
    }

    private PaymentNotification signed(PaymentNotification notification) {
        notification.setSign(signer.sign(notification.signedFields()));
        return notification;
    }

    private RefundNotification refunded(String refundNo) {
        RefundNotification notification = new RefundNotification(refundNo, "CR-" + refundNo, TradeFixture.PAY_VALUE, null);
        notification.setSign(signer.sign(notification.signedFields()));
        return notification;
    }

    private String couponCode(long orderNo) {
        return jdbc.queryForObject("SELECT verify_code FROM user_coupon WHERE coupon_no = ?", String.class, "P" + orderNo);
    }

    private String couponStatus(long orderNo) {
        return jdbc.queryForObject("SELECT status FROM user_coupon WHERE coupon_no = ?", String.class, "P" + orderNo);
    }

    private int count(String sql, Object arg) {
        return jdbc.queryForObject(sql, Integer.class, arg);
    }

    private AdminPrincipal merchant() {
        AdminPrincipal principal = new AdminPrincipal();
        principal.setAccountId(BASE + 900);
        principal.setMerchantId(fixture.merchantId);
        principal.setScopeType(AdminPrincipal.SCOPE_MERCHANT);
        return principal;
    }

    private static <T> T after(long delayMs, Callable<T> call) throws Exception {
        if (delayMs > 0) {
            Thread.sleep(delayMs);
        }
        return call.call();
    }

    private static Object attempt(Callable<?> call) throws Exception {
        try {
            call.call();
            return "OK";
        } catch (ApiStatusException e) {
            return e.getCode();
        }
    }

    private static void assertRejected(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, String code) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ApiStatusException.class,
                error -> assertThat(error.getCode()).isEqualTo(code));
    }

    private static <T> List<T> concurrently(int threads, Callable<T> task) throws Exception {
        List<Callable<T>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            tasks.add(task);
        }
        return concurrently(threads, tasks);
    }

    @SuppressWarnings("unchecked")
    private static <T> List<T> concurrently(int threads, List<? extends Callable<? extends T>> tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<T>> futures = new ArrayList<>();
        try {
            for (Callable<? extends T> task : tasks) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return (T) task.call();
                }));
            }
            start.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) {
                results.add(future.get(30, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }
}
