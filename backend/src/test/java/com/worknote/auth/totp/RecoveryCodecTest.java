package com.worknote.auth.totp;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class RecoveryCodecTest {

    @Test void generate_is12CharsFromUnambiguousAlphabet() {
        for (int i = 0; i < 100; i++) {
            String code = RecoveryCodec.generate();
            assertThat(code).hasSize(12).matches("[ABCDEFGHJKMNPQRSTUVWXYZ23456789]+");
        }
    }

    @Test void generate_isNotRepeating() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 100; i++) seen.add(RecoveryCodec.generate());
        assertThat(seen).hasSizeGreaterThan(95);
    }

    @Test void normalize_stripsSeparatorsAndUppercases() {
        assertThat(RecoveryCodec.normalize(" abcd-2345 efgh ")).isEqualTo("ABCD2345EFGH");
        assertThat(RecoveryCodec.normalize(null)).isEmpty();
    }
}
