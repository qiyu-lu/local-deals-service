package com.localdeals.trade.service;

import org.springframework.stereotype.Component;

/** Random, unguessable in-store verification codes. */
@Component
public class VerifyCodeGenerator {

    public static final int LENGTH = 16;
    /** Crockford base32: no I, L, O, U, so a code read aloud or typed at a counter is unambiguous. */
    public static final String ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";

    public String next() {
        throw new UnsupportedOperationException("M2: not implemented yet");
    }
}
