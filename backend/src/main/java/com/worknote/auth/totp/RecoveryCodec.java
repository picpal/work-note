package com.worknote.auth.totp;

import java.security.SecureRandom;

/**
 * 이메일 1회용 복구 코드 — 12자 영숫자(혼동문자 0/O·1/I/L 제외 31자, ≈59.4bit).
 * 기존 8자리 숫자(≈26.6bit)의 브루트포스 여지를 제거. 해시 저장은 PasswordHasher(PBKDF2) 재사용.
 */
public final class RecoveryCodec {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final char[] ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789".toCharArray();
    public static final int LENGTH = 12;

    private RecoveryCodec() {}

    public static String generate() {
        StringBuilder sb = new StringBuilder(LENGTH);
        for (int i = 0; i < LENGTH; i++) {
            sb.append(ALPHABET[RANDOM.nextInt(ALPHABET.length)]);
        }
        return sb.toString();
    }

    /** 사용자 입력 정규화 — 공백·하이픈 제거 + 대문자화 (이메일에서 복사 시 흔한 변형 흡수). */
    public static String normalize(String input) {
        return input == null ? "" : input.replaceAll("[\\s-]", "").toUpperCase();
    }
}
