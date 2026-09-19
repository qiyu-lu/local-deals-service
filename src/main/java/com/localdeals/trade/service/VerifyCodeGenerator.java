package com.localdeals.trade.service;

import org.springframework.stereotype.Component;

import java.security.SecureRandom;

/**
 * Random, unguessable in-store verification codes: 16 characters x 5 bits = 80 bits. With the
 * per-merchant verify rate limit, guessing any live code of another user is out of reach.
 */
@Component
public class VerifyCodeGenerator {

    public static final int LENGTH = 16;
    /** Crockford base32: no I, L, O, U, so a code read aloud or typed at a counter is unambiguous. */
    public static final String ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";

    private final SecureRandom random = new SecureRandom();

    public String next() {
        char[] code = new char[LENGTH];
        for (int i = 0; i < LENGTH; i++) {
            code[i] = ALPHABET.charAt(random.nextInt(ALPHABET.length()));
        }
        return new String(code);
    }
}
