package com.localdeals.trade.controller;


import com.localdeals.platform.dto.Result;
import com.localdeals.trade.service.IVoucherOrderService;
import com.localdeals.platform.service.TrustedClientIpResolver;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;

/**
 * <p>
 *  前端控制器
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@RestController
@RequestMapping("/voucher-order")
public class VoucherOrderController {
    @Resource
    private IVoucherOrderService voucherOrderService;
    @Resource
    private TrustedClientIpResolver clientIpResolver;
    @Resource
    private com.localdeals.trade.service.SeckillTokenService seckillTokenService;

    /** Stub for the red commit. */
    @GetMapping("/seckill/{id}/token")
    public Result seckillToken(@PathVariable("id") Long voucherId) {
        throw new UnsupportedOperationException("not implemented");
    }

    @PostMapping("/seckill/{id}")
    public Result seckillVoucher(@PathVariable("id") Long voucherId,
                                 @RequestHeader(value = "X-Seckill-Token", required = false) String seckillToken,
                                 HttpServletRequest request) {
        return voucherOrderService.seckillVoucher(voucherId, clientIpResolver.resolve(request));
        //return Result.fail("功能未完成");
    }

    @GetMapping("/status/{orderId}")
    public Result querySeckillOrderStatus(@PathVariable("orderId") Long orderId) {
        return voucherOrderService.querySeckillOrderStatus(orderId);
    }
}
