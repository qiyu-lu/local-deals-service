package com.localdeals.trade.payment;

import java.util.Map;

/** HMAC-SHA256 over the canonical form {@code k1=v1&k2=v2...} with keys sorted. */
public class PaymentSigner {

    private final byte[] key;

    public PaymentSigner(String secret) {
        this.key = secret.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    public String sign(Map<String, String> fields) {
        throw new UnsupportedOperationException("M2: not implemented yet");
    }

    public boolean verify(Map<String, String> fields, String signature) {
        throw new UnsupportedOperationException("M2: not implemented yet");
    }
}
