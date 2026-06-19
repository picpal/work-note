package com.worknote.auth.totp;

import org.junit.jupiter.api.Test;
import java.security.SecureRandom;
import java.util.Base64;
import static org.assertj.core.api.Assertions.*;

class SecretCipherTest {
    private static String key32() {
        byte[] k = new byte[32]; new SecureRandom().nextBytes(k);
        return Base64.getEncoder().encodeToString(k);
    }

    @Test void roundTrip() {
        SecretCipher c = new SecretCipher(key32());
        String secret = "JBSWY3DPEHPK3PXP";
        String enc = c.encrypt(secret);
        assertThat(enc).isNotEqualTo(secret);
        assertThat(c.decrypt(enc)).isEqualTo(secret);
    }

    @Test void differentNoncePerEncrypt() {
        SecretCipher c = new SecretCipher(key32());
        assertThat(c.encrypt("X")).isNotEqualTo(c.encrypt("X"));  // 랜덤 nonce
    }

    @Test void wrongKeyFailsToDecrypt() {
        String enc = new SecretCipher(key32()).encrypt("X");
        assertThatThrownBy(() -> new SecretCipher(key32()).decrypt(enc))
            .isInstanceOf(IllegalStateException.class);
    }

    @Test void missingKeyMeansDisabled() {
        SecretCipher c = new SecretCipher("");
        assertThat(c.available()).isFalse();
        assertThatThrownBy(() -> c.encrypt("X")).isInstanceOf(IllegalStateException.class);
    }
}
