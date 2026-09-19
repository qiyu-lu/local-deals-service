package com.localdeals.trade.payment;

import com.localdeals.trade.service.PaymentCallbackService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Channel-facing endpoints, authenticated by the HMAC signature instead of a user token. Any
 * non-2xx answer (bad signature, unknown payment, database trouble) makes the channel retry.
 */
@RestController
public class PaymentCallbackController {

    private final PaymentCallbackService callbackService;

    public PaymentCallbackController(PaymentCallbackService callbackService) {
        this.callbackService = callbackService;
    }

    @PostMapping("/payment/callback")
    public String paid(@RequestBody PaymentNotification notification) {
        callbackService.onPaid(notification);
        return "SUCCESS";
    }

    @PostMapping("/payment/refund-callback")
    public String refunded(@RequestBody RefundNotification notification) {
        callbackService.onRefunded(notification);
        return "SUCCESS";
    }
}
