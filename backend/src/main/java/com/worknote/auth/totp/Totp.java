package com.worknote.auth.totp;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** RFC 6238 TOTP: HMAC-SHA1, 30초 step, 6자리. Google Authenticator 호환. 순수 함수. */
public final class Totp {
    public static final int PERIOD = 30;
    public static final int DIGITS = 6;
    public static final int WINDOW = 1;     // ±1 step 시계 흔들림 허용
    private static final int MOD = 1_000_000;
    private Totp() {}

    /** 주어진 epoch초의 코드(6자리, 0 패딩). */
    public static String codeAt(String base32Secret, long epochSeconds) {
        return codeForStep(Base32.decode(base32Secret), epochSeconds / PERIOD);
    }

    /**
     * 검증 — [step-WINDOW, step+WINDOW] 중 일치하고 lastStep보다 큰 step을 반환(재생 방지). 불일치/재생 시 -1.
     * 호출부는 반환값(>=0)을 last_step으로 저장한다.
     */
    public static long verify(String base32Secret, String code, long epochSeconds, long lastStep) {
        byte[] key = Base32.decode(base32Secret);
        long current = epochSeconds / PERIOD;
        for (long s = current - WINDOW; s <= current + WINDOW; s++) {
            if (s <= lastStep) continue;                       // 재생 방지: 이미 쓴 step 이하 거부
            if (MessageDigest.isEqual(codeForStep(key, s).getBytes(StandardCharsets.UTF_8), code.getBytes(StandardCharsets.UTF_8))) return s;
        }
        return -1L;
    }

    private static String codeForStep(byte[] key, long step) {
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(key, "HmacSHA1"));
            byte[] hmac = mac.doFinal(ByteBuffer.allocate(8).putLong(step).array());
            int offset = hmac[hmac.length - 1] & 0xf;
            int bin = ((hmac[offset] & 0x7f) << 24) | ((hmac[offset + 1] & 0xff) << 16)
                    | ((hmac[offset + 2] & 0xff) << 8) | (hmac[offset + 3] & 0xff);
            return String.format("%0" + DIGITS + "d", bin % MOD);
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA1 사용 불가", e);
        }
    }
}
