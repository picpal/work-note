package com.worknote.auth.totp;

public record RecoveryRow(String id, String userId, String salt, String codeHash, String expiresAt, int used, String createdAt) {}
