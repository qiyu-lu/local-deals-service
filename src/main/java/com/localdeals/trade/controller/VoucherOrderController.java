package com.localdeals.trade.controller;


import com.localdeals.platform.dto.Result;
import com.localdeals.platform.exception.ApiErrorCodes;
import com.localdeals.platform.exception.ApiStatusException;
import com.localdeals.platform.utils.UserHolder;
import com.localdeals.trade.service.IVoucherOrderService;
import com.localdeals.platform.service.TrustedClientIpResolver;
import com.localdeals.trade.service.SeckillTokenService;
import org.springframework.http.HttpStatus;
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
    private SeckillTokenService seckillTokenService;

    public static final String SECKILL_TOKEN_HEADER = "X-Seckill-Token";

    /** Only while the activity is open; bound to the caller and the voucher, valid for minutes. */
    @GetMapping("/seckill/{id}/token")
    public Result seckillToken(@PathVariable("id") Long voucherId) {
        return seckillTokenService.issue(UserHolder.getUser().getId(), voucherId);
    }

    @PostMapping("/seckill/{id}")
    public Result seckillVoucher(@PathVariable("id") Long voucherId,
                                 @RequestHeader(value = SECKILL_TOKEN_HEADER, required = false) String seckillToken,
                                 HttpServletRequest request) {
        if (seckillTokenService.isRequired()
                && !seckillTokenService.verify(UserHolder.getUser().getId(), voucherId, seckillToken)) {
            throw new ApiStatusException(HttpStatus.FORBIDDEN, ApiErrorCodes.SECKILL_TOKEN_INVALID,
                    "秒杀令牌无效或已过期，请刷新页面后重试");
        }
        return voucherOrderService.seckillVoucher(voucherId, clientIpResolver.resolve(request));
    }

    @GetMapping("/status/{orderId}")
    public Result querySeckillOrderStatus(@PathVariable("orderId") Long orderId) {
        return voucherOrderService.querySeckillOrderStatus(orderId);
    }
}
