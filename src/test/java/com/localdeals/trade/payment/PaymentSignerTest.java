package com.localdeals.trade.payment;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PaymentSignerTest {

    private final PaymentSigner signer = new PaymentSigner("unit-test-secret-0123456789");

    @Test
    void aSignedNotificationVerifies() {
        PaymentNotification notification = new PaymentNotification("P1-1", "TXN-1", 990L, null);

        String sign = signer.sign(notification.signedFields());

        assertThat(sign).matches("[0-9a-f]{64}");
        assertThat(signer.verify(notification.signedFields(), sign)).isTrue();
    }

    @Test
    void theCanonicalFormDoesNotDependOnFieldOrder() {
        Map<String, String> ab = new LinkedHashMap<>();
        ab.put("a", "1");
        ab.put("b", "2");
        Map<String, String> ba = new LinkedHashMap<>();
        ba.put("b", "2");
        ba.put("a", "1");

        assertThat(signer.sign(ab)).isEqualTo(signer.sign(ba));
    }

    @Test
    void aTamperedAmountOrAnotherKeyFailsVerification() {
        PaymentNotification genuine = new PaymentNotification("P1-1", "TXN-1", 990L, null);
        String sign = signer.sign(genuine.signedFields());

        PaymentNotification tampered = new PaymentNotification("P1-1", "TXN-1", 1L, null);
        assertThat(signer.verify(tampered.signedFields(), sign)).isFalse();
        assertThat(new PaymentSigner("another-secret-0123456789").verify(genuine.signedFields(), sign)).isFalse();
        assertThat(signer.verify(genuine.signedFields(), null)).isFalse();
        assertThat(signer.verify(genuine.signedFields(), "zz")).isFalse();
    }
}
