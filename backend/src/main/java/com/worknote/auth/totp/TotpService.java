package com.worknote.auth.totp;

import com.worknote.vault.VaultException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/** TOTP 등록/확인/검증/해제/초기화. 시드는 SecretCipher로 암호화 저장. */
@Service
public class TotpService {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String ISSUER = "work-note";

    private final TotpMapper mapper;
    private final SecretCipher cipher;
    private final Clock clock;

    public TotpService(TotpMapper mapper, SecretCipher cipher, Clock clock) {
        this.mapper = mapper;
        this.cipher = cipher;
        this.clock = clock;
    }

    private String now() { return LocalDateTime.now(clock).format(DateTimeFormatter.ISO_LOCAL_DATE_TIME); }
    private long epoch() { return Instant.now(clock).getEpochSecond(); }

    /** 시드 생성(또는 재생성) — enabled=0. otpauth URI 반환. 키 미구성 시 422. */
    @Transactional
    public String setup(String userId, String emp) {
        if (!cipher.available()) throw VaultException.invalid("2FA 키가 구성되지 않았습니다 — 관리자에게 문의하세요");
        byte[] raw = new byte[20];
        RANDOM.nextBytes(raw);
        String secret = Base32.encode(raw);
        // 기존 row 있으면 교체
        mapper.delete(userId);
        mapper.insert(new TotpRow(userId, cipher.encrypt(secret), 0, null, 0, now()));
        return otpauthUri(emp, secret);
    }

    @Transactional
    public boolean confirm(String userId, String code) {
        TotpRow row = mapper.find(userId);
        if (row == null) throw VaultException.invalid("먼저 2FA 등록을 시작하세요");
        long matched = Totp.verify(cipher.decrypt(row.secretEnc()), code, epoch(), row.lastStep());
        if (matched < 0) return false;
        mapper.updateLastStep(userId, matched);
        mapper.enable(userId, now());
        return true;
    }

    /** 로그인 단계 검증 — enabled row만. last_step 갱신(재생 방지). */
    @Transactional
    public boolean verifyLogin(String userId, String code) {
        TotpRow row = mapper.find(userId);
        if (row == null || row.enabled() != 1) return false;
        long matched = Totp.verify(cipher.decrypt(row.secretEnc()), code, epoch(), row.lastStep());
        if (matched < 0) return false;
        mapper.updateLastStep(userId, matched);
        return true;
    }

    public boolean isEnabled(String userId) {
        TotpRow row = mapper.find(userId);
        return row != null && row.enabled() == 1;
    }

    @Transactional
    public void disable(String userId) { mapper.delete(userId); }

    @Transactional
    public void reset(String userId) {
        mapper.delete(userId);
        mapper.invalidateRecovery(userId);
    }

    /**
     * 이미 등록된 row의 otpauth URI를 재건 — QR 재발급 엔드포인트(Task 13)용.
     * row 없으면 422.
     */
    public String otpauthUriForExisting(String userId, String emp) {
        TotpRow row = mapper.find(userId);
        if (row == null) throw VaultException.invalid("2FA 등록 정보가 없습니다");
        String secret = cipher.decrypt(row.secretEnc());
        return otpauthUri(emp, secret);
    }

    private static String otpauthUri(String emp, String secret) {
        // label = "issuer:account" — colon은 otpauth URI 규약상 구분자이므로 인코딩 안 함
        String encodedEmp = URLEncoder.encode(emp, StandardCharsets.UTF_8).replace("+", "%20");
        String label = ISSUER + ":" + encodedEmp;
        return "otpauth://totp/" + label + "?secret=" + secret
            + "&issuer=" + ISSUER + "&algorithm=SHA1&digits=6&period=30";
    }

    /** 테스트 전용 — 복호화한 시드. 프로덕션 호출 금지. */
    public String currentSecretForTest(String userId) {
        return cipher.decrypt(mapper.find(userId).secretEnc());
    }
}
