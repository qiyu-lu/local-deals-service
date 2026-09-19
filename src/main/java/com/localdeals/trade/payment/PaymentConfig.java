package com.localdeals.trade.payment;

import com.localdeals.trade.config.PaymentProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Slf4j
@Configuration
public class PaymentConfig {

    @Bean
    public PaymentSigner paymentSigner(PaymentProperties properties) {
        return new PaymentSigner(properties.getCallbackSecret());
    }

    /** The only channel in this project: a mock that calls this application back over HTTP. */
    @Bean(destroyMethod = "shutdown")
    public MockPaymentChannel paymentChannel(PaymentSigner signer, PaymentProperties properties) {
        RestClient client = RestClient.builder().baseUrl(properties.getMockChannel().getNotifyBaseUrl()).build();
        MockPaymentChannel.Transport http = new MockPaymentChannel.Transport() {
            @Override
            public boolean deliverPayment(PaymentNotification notification) {
                return post("/payment/callback", notification);
            }

            @Override
            public boolean deliverRefund(RefundNotification notification) {
                return post("/payment/refund-callback", notification);
            }

            private boolean post(String path, Object body) {
                try {
                    return client.post().uri(path).body(body).retrieve().toBodilessEntity()
                            .getStatusCode().is2xxSuccessful();
                } catch (RuntimeException e) {
                    log.debug("Mock channel callback {} failed; it will retry: {}", path, e.getMessage());
                    return false;
                }
            }
        };
        return new MockPaymentChannel(signer, http, properties.getMockChannel());
    }
}
