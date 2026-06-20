package com.worknote.auth.totp;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * TOTP 시드 at-rest 암호화 — AES-256-GCM. 키 = WORKNOTE_2FA_KEY (Base64 32바이트).
 * 저장 포맷: base64( nonce(12B) || ciphertext+tag ). 키 미설정 시 available()=false.
 */
@Component
public class SecretCipher {
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final SecretKeySpec key;   // null이면 미구성

    public SecretCipher(@Value("${worknote.totp.key:}") String keyBase64) {
        if (keyBase64 == null || keyBase64.isBlank()) {
            this.key = null;
        } else {
            byte[] raw = Base64.getDecoder().decode(keyBase64.trim());
            if (raw.length != 32) throw new IllegalStateException("WORKNOTE_2FA_KEY는 Base64(32바이트)여야 합니다 (현재 " + raw.length + "B)");
            this.key = new SecretKeySpec(raw, "AES");
        }
    }

    public boolean available() { return key != null; }

    public String encrypt(String plaintext) {
        require();
        try {
            byte[] nonce = new byte[NONCE_BYTES];
            RANDOM.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
            byte[] ct = cipher.doFinal(plaintext.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            byte[] out = new byte[nonce.length + ct.length];
            System.arraycopy(nonce, 0, out, 0, nonce.length);
            System.arraycopy(ct, 0, out, nonce.length, ct.length);
            return Base64.getEncoder().encodeToString(out);
        } catch (Exception e) {
            throw new IllegalStateException("시드 암호화 실패", e);
        }
    }

    public String decrypt(String stored) {
        require();
        try {
            byte[] all = Base64.getDecoder().decode(stored);
            byte[] nonce = java.util.Arrays.copyOfRange(all, 0, NONCE_BYTES);
            byte[] ct = java.util.Arrays.copyOfRange(all, NONCE_BYTES, all.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
            return new String(cipher.doFinal(ct), java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("시드 복호화 실패 (키 변경/손상)", e);
        }
    }

    private void require() {
        if (key == null) throw new IllegalStateException("2FA 키(WORKNOTE_2FA_KEY)가 구성되지 않았습니다");
    }
}
