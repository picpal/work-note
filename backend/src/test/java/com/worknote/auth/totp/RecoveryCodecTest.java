package com.worknote.auth.totp;

import org.junit.jupiter.api.Test;
import com.worknote.auth.PasswordHasher;
import static org.assertj.core.api.Assertions.assertThat;

class RecoveryCodecTest {
    @Test void generatesEightDigits() {
        for (int i = 0; i < 50; i++) {
            String code = RecoveryCodec.generate();
            assertThat(code).matches("\\d{8}");
        }
    }
    @Test void hashVerifiesWithPasswordHasher() {
        String code = RecoveryCodec.generate();
        String salt = PasswordHasher.newSalt();
        String hash = PasswordHasher.hash(code, salt);
        assertThat(PasswordHasher.verify(code, salt, hash)).isTrue();
        assertThat(PasswordHasher.verify("00000000", salt, hash)).isFalse();
    }
}
