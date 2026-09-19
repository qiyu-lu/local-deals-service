package com.localdeals.trade.service;

import com.localdeals.platform.exception.ApiErrorCodes;
import com.localdeals.platform.exception.ApiStatusException;
import com.localdeals.trade.entity.PaymentRecord;
import com.localdeals.trade.entity.TradeOrder;
import com.localdeals.trade.mapper.PaymentRecordMapper;
import com.localdeals.trade.mapper.TradeOrderMapper;
import com.localdeals.trade.payment.PaymentChannel;
import lombok.AllArgsConstructor;
import lombok.Data;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Prepay: turns a PENDING_PAY order into a channel payment the user can complete. */
@Service
public class PaymentService {

    @Data
    @AllArgsConstructor
    public static class Prepay {
        private String payNo;
        private long amount;
        private String cashierUrl;
    }

    private final TradeOrderMapper orderMapper;
    private final PaymentRecordMapper paymentMapper;
    private final PaymentChannel channel;

    public PaymentService(TradeOrderMapper orderMapper, PaymentRecordMapper paymentMapper, PaymentChannel channel) {
        this.orderMapper = orderMapper;
        this.paymentMapper = paymentMapper;
        this.channel = channel;
    }

    /**
     * One open (WAITING) payment per order, reused by repeated prepays. The order row lock
     * serialises concurrent prepays; pay_no = order_no-sequence needs no id service.
     */
    @Transactional
    public Prepay prepay(long userId, long orderNo) {
        TradeOrder order = orderMapper.selectForUpdate(orderNo);
        if (order == null || order.getUserId() != userId) {
            throw new ApiStatusException(HttpStatus.NOT_FOUND, ApiErrorCodes.ORDER_NOT_FOUND, "订单不存在");
        }
        if (orderMapper.countPayable(orderNo) != 1) {
            throw new ApiStatusException(HttpStatus.CONFLICT, ApiErrorCodes.ORDER_NOT_PAYABLE, "订单已关闭或已支付");
        }
        PaymentRecord payment = paymentMapper.selectWaiting(orderNo);
        if (payment == null) {
            payment = new PaymentRecord();
            payment.setPayNo(orderNo + "-" + (paymentMapper.countByOrder(orderNo) + 1));
            payment.setOrderNo(orderNo);
            payment.setUserId(userId);
            payment.setAmount(order.getAmount());
            payment.setChannel(PaymentChannel.NAME_MOCK);
            payment.setStatus(PaymentRecord.WAITING);
            paymentMapper.insert(payment);
        }
        String cashierUrl = channel.createPayment(payment.getPayNo(), payment.getAmount());
        return new Prepay(payment.getPayNo(), payment.getAmount(), cashierUrl);
    }
}
