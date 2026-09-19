package com.localdeals.trade.payment;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/** HMAC-SHA256 over the canonical form {@code k1=v1&k2=v2...} with keys sorted. */
public class PaymentSigner {

    private static final String ALGORITHM = "HmacSHA256";

    private final SecretKeySpec key;

    public PaymentSigner(String secret) {
        this.key = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), ALGORITHM);
    }

    public String sign(Map<String, String> fields) {
        String canonical = new TreeMap<>(fields).entrySet().stream()
                .map(field -> field.getKey() + "=" + (field.getValue() == null ? "" : field.getValue()))
                .collect(Collectors.joining("&"));
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(key);
            return HexFormat.of().formatHex(mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 unavailable", e);
        }
    }

    /** Constant-time comparison, so the check does not leak how many leading characters matched. */
    public boolean verify(Map<String, String> fields, String signature) {
        if (signature == null) {
            return false;
        }
        return MessageDigest.isEqual(sign(fields).getBytes(StandardCharsets.US_ASCII),
                signature.getBytes(StandardCharsets.US_ASCII));
    }
}
