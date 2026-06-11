package com.worknote.auth;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class PasswordHasherTest {

    @Test
    void newSaltIsRandomBase64() {
        String s1 = PasswordHasher.newSalt();
        String s2 = PasswordHasher.newSalt();
        assertThat(s1).isNotEqualTo(s2);
        assertThat(java.util.Base64.getDecoder().decode(s1)).hasSize(16);
    }

    @Test
    void hashIsDeterministicForSameSalt() {
        String salt = PasswordHasher.newSalt();
        assertThat(PasswordHasher.hash("pw-1234", salt))
            .isEqualTo(PasswordHasher.hash("pw-1234", salt));
    }

    @Test
    void differentSaltDifferentHash() {
        assertThat(PasswordHasher.hash("pw-1234", PasswordHasher.newSalt()))
            .isNotEqualTo(PasswordHasher.hash("pw-1234", PasswordHasher.newSalt()));
    }

    @Test
    void verifyMatchesAndRejects() {
        String salt = PasswordHasher.newSalt();
        String hash = PasswordHasher.hash("correct-pw", salt);
        assertThat(PasswordHasher.verify("correct-pw", salt, hash)).isTrue();
        assertThat(PasswordHasher.verify("wrong-pw", salt, hash)).isFalse();
    }
}
