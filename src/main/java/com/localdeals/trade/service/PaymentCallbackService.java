package com.localdeals.trade.service;

import com.localdeals.platform.exception.ApiErrorCodes;
import com.localdeals.platform.exception.ApiStatusException;
import com.localdeals.trade.entity.CouponStatus;
import com.localdeals.trade.entity.OrderEvent;
import com.localdeals.trade.entity.OrderStatus;
import com.localdeals.trade.entity.PaymentRecord;
import com.localdeals.trade.entity.RefundRecord;
import com.localdeals.trade.entity.TradeOrder;
import com.localdeals.trade.mapper.PaymentRecordMapper;
import com.localdeals.trade.mapper.RefundRecordMapper;
import com.localdeals.trade.mapper.TradeOrderMapper;
import com.localdeals.trade.mapper.UserCouponMapper;
import com.localdeals.trade.payment.PaymentNotification;
import com.localdeals.trade.payment.PaymentSigner;
import com.localdeals.trade.payment.RefundNotification;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Handles the channel's signed, repeated and unordered callbacks. Idempotency comes from locking
 * the payment (or refund) row and acting only on its WAITING (PENDING) -> SUCCESS change; the
 * race with the timeout close is decided by the order compare-and-set.
 */
@Slf4j
@Service
public class PaymentCallbackService {

    public enum PaidOutcome {
        /** This callback paid the order and issued its coupon. */
        PAID,
        /** The payment was already settled by an earlier copy of this callback. */
        DUPLICATE,
        /** The money arrived but the order was closed (or already paid): refunded automatically. */
        AUTO_REFUND,
        /** Amount mismatch: recorded as ABNORMAL for manual review, never applied. */
        ABNORMAL
    }

    public enum RefundOutcome {
        REFUNDED,
        DUPLICATE
    }

    private final PaymentSigner signer;
    private final PaymentRecordMapper paymentMapper;
    private final RefundRecordMapper refundMapper;
    private final TradeOrderMapper orderMapper;
    private final UserCouponMapper couponMapper;
    private final OrderStateMachine stateMachine;
    private final CouponIssuer couponIssuer;
    private final RefundService refundService;
    private final ReservationReleaseService releaseService;

    public PaymentCallbackService(PaymentSigner signer, PaymentRecordMapper paymentMapper,
                                  RefundRecordMapper refundMapper, TradeOrderMapper orderMapper,
                                  UserCouponMapper couponMapper, OrderStateMachine stateMachine,
                                  CouponIssuer couponIssuer, RefundService refundService,
                                  ReservationReleaseService releaseService) {
        this.signer = signer;
        this.paymentMapper = paymentMapper;
        this.refundMapper = refundMapper;
        this.orderMapper = orderMapper;
        this.couponMapper = couponMapper;
        this.stateMachine = stateMachine;
        this.couponIssuer = couponIssuer;
        this.refundService = refundService;
        this.releaseService = releaseService;
    }

    @Transactional
    public PaidOutcome onPaid(PaymentNotification notification) {
        requireSigned(signer.verify(notification.signedFields(), notification.getSign()));
        PaymentRecord payment = paymentMapper.selectForUpdate(notification.getPayNo());
        if (payment == null) {
            // Unknown to us (yet): answer non-2xx so the channel keeps retrying.
            throw new ApiStatusException(HttpStatus.NOT_FOUND, ApiErrorCodes.PAYMENT_NOT_FOUND, "支付单不存在");
        }
        if (!PaymentRecord.WAITING.equals(payment.getStatus())) {
            if (!notification.getChannelTxnNo().equals(payment.getChannelTxnNo())) {
                log.error("Second channel transaction for one payment. payNo={} recorded={} received={}",
                        payment.getPayNo(), payment.getChannelTxnNo(), notification.getChannelTxnNo());
            }
            return PaidOutcome.DUPLICATE;
        }
        if (!payment.getAmount().equals(notification.getAmount())) {
            paymentMapper.settle(payment.getPayNo(), PaymentRecord.ABNORMAL, notification.getChannelTxnNo(),
                    notification.getAmount());
            log.error("Payment amount mismatch; recorded as ABNORMAL. payNo={} expected={} received={}",
                    payment.getPayNo(), payment.getAmount(), notification.getAmount());
            return PaidOutcome.ABNORMAL;
        }
        paymentMapper.settle(payment.getPayNo(), PaymentRecord.SUCCESS, notification.getChannelTxnNo(),
                notification.getAmount());
        payment.setStatus(PaymentRecord.SUCCESS);

        if (stateMachine.fire(payment.getOrderNo(), OrderEvent.PAY, "CHANNEL")) {
            couponIssuer.issueForOrder(orderMapper.selectById(payment.getOrderNo()));
            return PaidOutcome.PAID;
        }
        // The close (or another payment) won the order. We hold the customer's money: return it.
        TradeOrder order = orderMapper.selectById(payment.getOrderNo());
        String reason = order.getStatus() == OrderStatus.CLOSED ? "PAID_AFTER_CLOSE" : "DUPLICATE_PAYMENT";
        refundService.createAutoRefund(payment, reason);
        log.warn("Payment arrived for an order in {}; refunding automatically. payNo={}",
                order.getStatus(), payment.getPayNo());
        return PaidOutcome.AUTO_REFUND;
    }

    @Transactional
    public RefundOutcome onRefunded(RefundNotification notification) {
        requireSigned(signer.verify(notification.signedFields(), notification.getSign()));
        RefundRecord refund = refundMapper.selectForUpdate(notification.getRefundNo());
        if (refund == null) {
            throw new ApiStatusException(HttpStatus.NOT_FOUND, ApiErrorCodes.PAYMENT_NOT_FOUND, "退款单不存在");
        }
        if (RefundRecord.SUCCESS.equals(refund.getStatus())) {
            return RefundOutcome.DUPLICATE;
        }
        if (!refund.getAmount().equals(notification.getAmount())) {
            throw new IllegalStateException("Refund amount mismatch. refundNo=" + refund.getRefundNo());
        }
        refundMapper.markSucceeded(refund.getRefundNo(), notification.getChannelRefundNo());
        if (RefundRecord.TYPE_USER.equals(refund.getType())) {
            if (!stateMachine.fire(refund.getOrderNo(), OrderEvent.REFUND_SUCCESS, "CHANNEL")) {
                throw new IllegalStateException("Refunded order is not REFUNDING. orderNo=" + refund.getOrderNo());
            }
            couponMapper.changeStatus("P" + refund.getOrderNo(), CouponStatus.FROZEN.name(), CouponStatus.REFUNDED.name());
            releaseService.returnUnit(orderMapper.selectById(refund.getOrderNo()));
        }
        return RefundOutcome.REFUNDED;
    }

    private static void requireSigned(boolean valid) {
        if (!valid) {
            throw new ApiStatusException(HttpStatus.UNAUTHORIZED, ApiErrorCodes.PAYMENT_SIGNATURE_INVALID, "签名无效");
        }
    }
}
