package com.localdeals.controller;


import com.localdeals.dto.Result;
import com.localdeals.service.IVoucherOrderService;
import com.localdeals.service.TrustedClientIpResolver;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;

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

    @PostMapping("/seckill/{id}")
    public Result seckillVoucher(@PathVariable("id") Long voucherId,
                                 HttpServletRequest request) {
        return voucherOrderService.seckillVoucher(voucherId, clientIpResolver.resolve(request));
        //return Result.fail("功能未完成");
    }

    @GetMapping("/status/{orderId}")
    public Result querySeckillOrderStatus(@PathVariable("orderId") Long orderId) {
        return voucherOrderService.querySeckillOrderStatus(orderId);
    }
}
