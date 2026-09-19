package com.localdeals.trade.payment;

import com.localdeals.trade.config.PaymentProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

class MockPaymentChannelTest {

    private final PaymentSigner signer = new PaymentSigner("unit-test-secret-0123456789");
    private final List<PaymentNotification> payments = new CopyOnWriteArrayList<>();
    private final List<RefundNotification> refunds = new CopyOnWriteArrayList<>();
    private final AtomicInteger failuresLeft = new AtomicInteger();
    private final MockPaymentChannel channel = new MockPaymentChannel(signer, new MockPaymentChannel.Transport() {
        @Override
        public boolean deliverPayment(PaymentNotification notification) {
            if (failuresLeft.getAndDecrement() > 0) {
                return false;
            }
            payments.add(notification);
            return true;
        }

        @Override
        public boolean deliverRefund(RefundNotification notification) {
            refunds.add(notification);
            return true;
        }
    }, settings());

    @AfterEach
    void tearDown() {
        channel.shutdown();
    }

    @Test
    void aPaymentIsCalledBackSignedAndMoreThanOnce() {
        channel.createPayment("P9-1", 990L);

        String txn = channel.pay("P9-1");

        await().atMost(Duration.ofSeconds(5)).until(() -> payments.size() == 2);
        assertThat(payments).allSatisfy(notification -> {
            assertThat(notification.getPayNo()).isEqualTo("P9-1");
            assertThat(notification.getChannelTxnNo()).isEqualTo(txn);
            assertThat(notification.getAmount()).isEqualTo(990L);
            assertThat(signer.verify(notification.signedFields(), notification.getSign())).isTrue();
        });
    }

    @Test
    void payingTwiceReturnsTheSameTransaction() {
        channel.createPayment("P9-2", 990L);

        assertThat(channel.pay("P9-2")).isEqualTo(channel.pay("P9-2"));
    }

    @Test
    void aRejectedCallbackIsRetried() {
        failuresLeft.set(3);
        channel.createPayment("P9-3", 990L);

        channel.pay("P9-3");

        await().atMost(Duration.ofSeconds(5)).until(() -> payments.size() == 2);
    }

    @Test
    void anUnknownPaymentCannotBePaid() {
        assertThatThrownBy(() -> channel.pay("P9-404")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aRefundIsConfirmedOncePerRefundNoEvenIfRequestedAgain() {
        channel.createPayment("P9-4", 990L);
        channel.pay("P9-4");

        channel.refund("RU9", "P9-4", 990L);
        channel.refund("RU9", "P9-4", 990L);

        await().atMost(Duration.ofSeconds(5)).until(() -> refunds.size() >= 2);
        assertThat(refunds).extracting(RefundNotification::getChannelRefundNo).containsOnly(refunds.get(0).getChannelRefundNo());
        assertThat(signer.verify(refunds.get(0).signedFields(), refunds.get(0).getSign())).isTrue();
    }

    private static PaymentProperties.MockChannel settings() {
        PaymentProperties.MockChannel settings = new PaymentProperties.MockChannel();
        settings.setDuplicates(2);
        settings.setMaxAttempts(5);
        settings.setInitialBackoff(Duration.ofMillis(10));
        settings.setJitter(Duration.ofMillis(5));
        return settings;
    }
}
