package com.worknote.auth.totp;

import com.worknote.auth.PasswordHasher;
import com.worknote.auth.UserMapper;
import com.worknote.auth.UserRow;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

/** 이메일 1회용 복구 코드 발급·검증. 계정/이메일 유무를 응답으로 노출하지 않음(열거 방지 — 컨트롤러가 균등 응답). */
@Service
public class RecoveryService {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(RecoveryService.class);
    private static final int EXPIRY_MINUTES = 10;

    private final UserMapper users;
    private final TotpMapper totp;
    private final MailSender mail;
    private final Clock clock;

    public RecoveryService(UserMapper users, TotpMapper totp, MailSender mail, Clock clock) {
        this.users = users; this.totp = totp; this.mail = mail; this.clock = clock;
    }

    /** 코드 발급 + 메일 발송. 조건 미충족(미존재/이메일없음/2FA미사용/메일비활성)이면 조용히 skip. */
    @Transactional
    public void request(String emp) {
        UserRow user = users.findByEmp(emp);
        if (user == null || user.email() == null || user.email().isBlank()) return;
        if (!mail.available()) return;
        TotpRow t = totp.find(user.id());
        if (t == null || t.enabled() != 1) return;

        String code = RecoveryCodec.generate();
        String salt = PasswordHasher.newSalt();
        LocalDateTime now = LocalDateTime.now(clock);
        totp.invalidateRecovery(user.id());   // 기존 미사용 코드 무효화
        totp.insertRecovery(new RecoveryRow(
            "rc-" + UUID.randomUUID(), user.id(), salt, PasswordHasher.hash(code, salt),
            now.plusMinutes(EXPIRY_MINUTES).format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
            0, now.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)));
        try {
            mail.send(user.email(), "[work-note] 2FA 복구 코드",
                "복구 코드: " + code + "\n10분 내에 입력하세요. 입력 후 2FA를 다시 등록해야 합니다.");
        } catch (Exception e) {
            // 메일 발송 실패도 조용히 흡수 — 컨트롤러 균등응답 보장(계정 열거 방지)
            log.warn("복구 코드 메일 발송 실패 (userId={}): {}", user.id(), e.getMessage());
        }
    }

    /** 검증 성공 시 userId 반환(컨트롤러가 로그인 승격 + 2FA 폐기). 실패 시 null. */
    @Transactional
    public String verify(String emp, String code) {
        UserRow user = users.findByEmp(emp);
        if (user == null) return null;
        RecoveryRow rc = totp.findLatestRecovery(user.id());
        if (rc == null || rc.used() == 1) return null;
        if (LocalDateTime.now(clock).isAfter(LocalDateTime.parse(rc.expiresAt()))) return null;
        if (!PasswordHasher.verify(code, rc.salt(), rc.codeHash())) return null;
        totp.markRecoveryUsed(rc.id());
        return user.id();
    }
}
