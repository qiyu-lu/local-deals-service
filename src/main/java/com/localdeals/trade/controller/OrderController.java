package com.localdeals.trade.controller;

import com.localdeals.platform.dto.Result;
import com.localdeals.platform.exception.ApiErrorCodes;
import com.localdeals.platform.exception.ApiStatusException;
import com.localdeals.platform.utils.UserHolder;
import com.localdeals.trade.entity.TradeOrder;
import com.localdeals.trade.mapper.TradeOrderMapper;
import com.localdeals.trade.service.PaymentService;
import com.localdeals.trade.service.RefundService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The buyer's side of the order lifecycle. */
@RestController
@RequestMapping("/orders")
public class OrderController {

    private static final int LIST_LIMIT = 50;

    private final TradeOrderMapper orderMapper;
    private final PaymentService paymentService;
    private final RefundService refundService;

    public OrderController(TradeOrderMapper orderMapper, PaymentService paymentService, RefundService refundService) {
        this.orderMapper = orderMapper;
        this.paymentService = paymentService;
        this.refundService = refundService;
    }

    @GetMapping
    public Result mine() {
        return Result.ok(orderMapper.selectByUser(UserHolder.getUser().getId(), LIST_LIMIT));
    }

    @GetMapping("/{orderNo}")
    public Result one(@PathVariable("orderNo") Long orderNo) {
        TradeOrder order = orderMapper.selectById(orderNo);
        if (order == null || !order.getUserId().equals(UserHolder.getUser().getId())) {
            throw new ApiStatusException(HttpStatus.NOT_FOUND, ApiErrorCodes.ORDER_NOT_FOUND, "订单不存在");
        }
        return Result.ok(order);
    }

    @PostMapping("/{orderNo}/pay")
    public Result pay(@PathVariable("orderNo") Long orderNo) {
        return Result.ok(paymentService.prepay(UserHolder.getUser().getId(), orderNo));
    }

    @PostMapping("/{orderNo}/refund")
    public Result refund(@PathVariable("orderNo") Long orderNo) {
        return Result.ok(refundService.apply(UserHolder.getUser().getId(), orderNo));
    }
}
