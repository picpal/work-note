package com.worknote.auth;

import org.junit.jupiter.api.Test;
import java.util.Base64;
import static org.assertj.core.api.Assertions.assertThat;

class PasswordHasherTest {

    @Test
    void newSaltIsRandomBase64() {
        String s1 = PasswordHasher.newSalt();
        String s2 = PasswordHasher.newSalt();
        assertThat(s1).isNotEqualTo(s2);
        assertThat(Base64.getDecoder().decode(s1)).hasSize(16);
    }

    /**
     * Known-answer 벡터 핀 — PBKDF2-HMAC-SHA256, 120,000 iterations, 256-bit key 고정 검증.
     * salt는 사용자별로 별도 저장되므로 기존 해시 호환성은 이 파라미터 불변에 전적으로 의존한다.
     * 이 테스트가 깨지면 파라미터가 바뀐 것 — 저장된 해시 전체가 무효화됨(마이그레이션 필요).
     */
    @Test
    void hashMatchesPinnedVector() {
        String zeroSalt = "AAAAAAAAAAAAAAAAAAAAAA==";   // 16바이트 0
        assertThat(PasswordHasher.hash("pw-1234", zeroSalt))
            .isEqualTo("SF7zE9+UcP1FbWR9wZpL4emcTEy0dKTcnXt7djuyZqI=");
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
