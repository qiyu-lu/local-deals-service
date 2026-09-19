package com.localdeals.trade.service;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class VerifyCodeGeneratorTest {

    private final VerifyCodeGenerator generator = new VerifyCodeGenerator();

    @Test
    void codesAreSixteenCrockfordCharactersSoEightyBitsOfEntropy() {
        String code = generator.next();

        assertThat(code).hasSize(VerifyCodeGenerator.LENGTH);
        assertThat(code.chars()).allMatch(c -> VerifyCodeGenerator.ALPHABET.indexOf(c) >= 0);
        assertThat(VerifyCodeGenerator.ALPHABET).hasSize(32).doesNotContain("I", "L", "O", "U");
    }

    @Test
    void codesDoNotRepeat() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 100_000; i++) {
            seen.add(generator.next());
        }
        assertThat(seen).hasSize(100_000);
    }
}
