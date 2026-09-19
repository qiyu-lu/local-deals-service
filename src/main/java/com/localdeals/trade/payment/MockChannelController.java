package com.localdeals.trade.payment;

import com.localdeals.platform.dto.Result;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** The mock channel's cashier: "the user paid". The channel then calls us back asynchronously. */
@RestController
public class MockChannelController {

    private final PaymentChannel channel;

    public MockChannelController(PaymentChannel channel) {
        this.channel = channel;
    }

    @PostMapping("/mock-channel/payments/{payNo}/pay")
    public Result pay(@PathVariable("payNo") String payNo) {
        if (!(channel instanceof MockPaymentChannel mock)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        return Result.ok(mock.pay(payNo));
    }
}
