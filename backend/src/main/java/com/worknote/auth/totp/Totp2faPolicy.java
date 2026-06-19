package com.worknote.auth.totp;

import java.time.LocalDateTime;

/** admin 2FA 강제/유예 판정 — 순수 함수. */
public final class Totp2faPolicy {
    private Totp2faPolicy() {}

    /** admin이고 TOTP 미등록이면 강제 대상. */
    public static boolean enforced(boolean isAdmin, boolean totpEnabled) {
        return isAdmin && !totpEnabled;
    }

    /** 유예 만료 = graceStart + graceDays < now. graceStart null이면 아직 시작 안 함(미만료). */
    public static boolean graceExpired(LocalDateTime graceStart, int graceDays, LocalDateTime now) {
        if (graceStart == null) return false;
        return now.isAfter(graceStart.plusDays(graceDays));
    }
}
