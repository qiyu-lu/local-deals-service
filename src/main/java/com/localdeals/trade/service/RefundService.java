package com.localdeals.trade.service;

import com.localdeals.platform.exception.ApiErrorCodes;
import com.localdeals.platform.exception.ApiStatusException;
import com.localdeals.trade.config.PaymentProperties;
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
import com.localdeals.trade.payment.PaymentChannel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;

/** User refunds of paid orders, automatic refunds of late payments, and their channel requests. */
@Slf4j
@Service
public class RefundService {

    private final TradeOrderMapper orderMapper;
    private final PaymentRecordMapper paymentMapper;
    private final RefundRecordMapper refundMapper;
    private final UserCouponMapper couponMapper;
    private final OrderStateMachine stateMachine;
    private final PaymentChannel channel;
    private final PaymentProperties properties;

    public RefundService(TradeOrderMapper orderMapper, PaymentRecordMapper paymentMapper,
                         RefundRecordMapper refundMapper, UserCouponMapper couponMapper,
                         OrderStateMachine stateMachine, PaymentChannel channel, PaymentProperties properties) {
        this.orderMapper = orderMapper;
        this.paymentMapper = paymentMapper;
        this.refundMapper = refundMapper;
        this.couponMapper = couponMapper;
        this.stateMachine = stateMachine;
        this.channel = channel;
        this.properties = properties;
    }

    /**
     * PAID -> REFUNDING with the coupon frozen in the same transaction, so the shop can no longer
     * verify it. The order row is locked first, the same order verification uses.
     */
    @Transactional
    public RefundRecord apply(long userId, long orderNo) {
        TradeOrder order = orderMapper.selectForUpdate(orderNo);
        if (order == null || order.getUserId() != userId) {
            throw new ApiStatusException(HttpStatus.NOT_FOUND, ApiErrorCodes.ORDER_NOT_FOUND, "订单不存在");
        }
        if (order.getStatus() != OrderStatus.PAID) {
            throw notRefundable(order.getStatus());
        }
        if (!stateMachine.fire(orderNo, OrderEvent.REFUND_APPLY, "USER:" + userId)) {
            throw new IllegalStateException("Locked PAID order refused REFUND_APPLY. orderNo=" + orderNo);
        }
        String couponNo = "P" + orderNo;
        if (couponMapper.changeStatus(couponNo, CouponStatus.AVAILABLE.name(), CouponStatus.FROZEN.name()) != 1 &&
                couponMapper.changeStatus(couponNo, CouponStatus.EXPIRED.name(), CouponStatus.FROZEN.name()) != 1) {
            // Rolls the order back to PAID.
            throw new ApiStatusException(HttpStatus.CONFLICT, ApiErrorCodes.ORDER_NOT_REFUNDABLE, "券状态不允许退款");
        }
        PaymentRecord payment = paymentMapper.selectFirstSuccess(orderNo);
        if (payment == null) {
            throw new IllegalStateException("PAID order has no successful payment. orderNo=" + orderNo);
        }
        return createRefund("RU" + orderNo, payment, RefundRecord.TYPE_USER, "USER_REQUEST");
    }

    /** Money arrived for an order that did not take it (closed, or paid by another payment). */
    @Transactional(propagation = Propagation.MANDATORY)
    public RefundRecord createAutoRefund(PaymentRecord payment, String reason) {
        return createRefund("RA" + payment.getPayNo(), payment, RefundRecord.TYPE_AUTO, reason);
    }

    /** Asks the channel again for refunds it has not confirmed; returns how many were requested. */
    public int retryStale(int limit) {
        List<RefundRecord> stale = refundMapper.selectStalePending(properties.getRefundRetryAfter().getSeconds(), limit);
        stale.forEach(this::request);
        return stale.size();
    }

    private RefundRecord createRefund(String refundNo, PaymentRecord payment, String type, String reason) {
        RefundRecord refund = new RefundRecord();
        refund.setRefundNo(refundNo);
        refund.setOrderNo(payment.getOrderNo());
        refund.setPayNo(payment.getPayNo());
        refund.setUserId(payment.getUserId());
        refund.setAmount(payment.getAmount());
        refund.setType(type);
        refund.setReason(reason);
        refund.setStatus(RefundRecord.PENDING);
        refundMapper.insert(refund);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                request(refund);
            }
        });
        return refund;
    }

    private void request(RefundRecord refund) {
        try {
            refundMapper.countRequest(refund.getRefundNo());
            channel.refund(refund.getRefundNo(), refund.getPayNo(), refund.getAmount());
        } catch (RuntimeException e) {
            log.warn("Refund request failed; the order scan retries it. refundNo={}", refund.getRefundNo(), e);
        }
    }

    private static ApiStatusException notRefundable(OrderStatus status) {
        switch (status) {
            case REFUNDING:
                return new ApiStatusException(HttpStatus.CONFLICT, ApiErrorCodes.REFUND_IN_PROGRESS, "退款处理中");
            case USED:
                return new ApiStatusException(HttpStatus.CONFLICT, ApiErrorCodes.ORDER_ALREADY_USED, "券已核销，不可退款");
            default:
                return new ApiStatusException(HttpStatus.CONFLICT, ApiErrorCodes.ORDER_NOT_REFUNDABLE, "订单状态不允许退款");
        }
    }
}
