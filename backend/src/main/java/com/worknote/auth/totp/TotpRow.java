package com.worknote.auth.totp;

public record TotpRow(String userId, String secretEnc, int enabled, String confirmedAt, long lastStep, String createdAt) {}
