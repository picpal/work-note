package com.worknote.auth;

/** user_credential 1행 — salt는 사용자별 분리 저장 (해시는 PasswordHasher). */
public record CredentialRow(String userId, String salt, String passwordHash) {}
