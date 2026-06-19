package com.worknote.auth.totp;

import java.security.SecureRandom;

/** 이메일 1회용 복구 코드 — 8자리 숫자. 해시 저장은 PasswordHasher(PBKDF2) 재사용. */
public final class RecoveryCodec {
    private static final SecureRandom RANDOM = new SecureRandom();
    private RecoveryCodec() {}

    public static String generate() {
        return String.format("%08d", RANDOM.nextInt(100_000_000));
    }
}
