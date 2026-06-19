package com.worknote.auth.totp;

import com.worknote.auth.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties = "spring.datasource.url=jdbc:sqlite:file:memdb-recov?mode=memory&cache=shared")
@Import(RecoveryServiceTest.FakeMailConfig.class)
class RecoveryServiceTest {
    static final AtomicReference<String> SENT_BODY = new AtomicReference<>();

    @TestConfiguration static class FakeMailConfig {
        @Bean @Primary MailSender fakeMail() {
            return new MailSender() {
                public boolean available() { return true; }
                public void send(String to, String subject, String body) { SENT_BODY.set(body); }
            };
        }
    }

    @Autowired RecoveryService recovery;
    @Autowired UserMapper users;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach void clean() {
        SENT_BODY.set(null);
        jdbc.update("DELETE FROM totp_recovery");
        jdbc.update("DELETE FROM user_totp");
        jdbc.update("DELETE FROM app_user");
        users.insert(new UserRow("u1","10001","a@corp.local","홍","admin","active",null));
        jdbc.update("INSERT INTO user_totp(user_id,secret_enc,enabled,last_step,created_at) VALUES('u1','X',1,0,'2026-06-19T00:00:00')");
    }

    @Test void requestSendsCodeWhenEmailPresentAndEnabled() {
        recovery.request("10001");
        assertThat(SENT_BODY.get()).isNotNull().containsPattern("\\d{8}");
    }

    @Test void requestSilentWhenNoEmail_noThrow_noSend() {
        users.update(new UserRow("u1","10001",null,"홍","admin","active",null));
        recovery.request("10001");                       // 균등 응답 — 예외 없음
        assertThat(SENT_BODY.get()).isNull();
    }

    @Test void verifyAcceptsSentCodeOnce() {
        recovery.request("10001");
        String code = extractCode(SENT_BODY.get());
        assertThat(recovery.verify("10001", code)).isEqualTo("u1");   // userId 반환
        assertThat(recovery.verify("10001", code)).isNull();          // 1회용 — 재사용 거부
    }

    @Test void verifyRejectsWrongCode() {
        recovery.request("10001");
        assertThat(recovery.verify("10001", "00000000")).isNull();
    }

    @Test void verifyRejectsExpiredCode() {
        // 과거 만료·미사용 복구 코드를 직접 INSERT — 만료 게이트 검증(Clock 오버라이드 불필요)
        String code = "12345678";
        String salt = PasswordHasher.newSalt();
        jdbc.update("INSERT INTO totp_recovery(id,user_id,salt,code_hash,expires_at,used,created_at) "
                + "VALUES(?,?,?,?,?,0,?)",
            "rc-" + UUID.randomUUID(), "u1", salt, PasswordHasher.hash(code, salt),
            "2020-01-01T00:00:00", "2020-01-01T00:00:00");
        assertThat(recovery.verify("10001", code)).isNull();
    }

    private static String extractCode(String body) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d{8})").matcher(body);
        m.find(); return m.group(1);
    }
}
