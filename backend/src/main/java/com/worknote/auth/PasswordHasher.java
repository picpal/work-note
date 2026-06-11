package com.worknote.auth;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/** PBKDF2-HMAC-SHA256 비밀번호 해시. salt는 사용자별 — user_credential 테이블에 별도 저장. */
public final class PasswordHasher {

    private static final int ITERATIONS = 120_000;
    private static final int KEY_BITS = 256;
    private static final int SALT_BYTES = 16;
    private static final SecureRandom RANDOM = new SecureRandom();

    private PasswordHasher() {}

    public static String newSalt() {
        byte[] salt = new byte[SALT_BYTES];
        RANDOM.nextBytes(salt);
        return Base64.getEncoder().encodeToString(salt);
    }

    public static String hash(String password, String saltBase64) {
        byte[] key = hashBytes(password, Base64.getDecoder().decode(saltBase64));
        return Base64.getEncoder().encodeToString(key);
    }

    public static boolean verify(String password, String saltBase64, String expectedHashBase64) {
        byte[] actual = hashBytes(password, Base64.getDecoder().decode(saltBase64));
        byte[] expected = Base64.getDecoder().decode(expectedHashBase64);
        return MessageDigest.isEqual(actual, expected);   // 타이밍 공격 방지 비교
    }

    private static byte[] hashBytes(String password, byte[] salt) {
        PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, ITERATIONS, KEY_BITS);
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                .generateSecret(spec).getEncoded();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("PBKDF2 사용 불가", e);
        } finally {
            spec.clearPassword();   // 메모리에서 비밀번호 사본 제거
        }
    }
}
