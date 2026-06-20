package com.worknote.auth.totp;

import com.worknote.auth.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.assertj.core.api.Assertions.*;

/** 메일 발송 실패(SMTP 장애) 시에도 request가 예외 없이 반환되는지 검증 — 계정 열거 방지(균등응답). */
@SpringBootTest(properties = "spring.datasource.url=jdbc:sqlite:file:memdb-recov-fail?mode=memory&cache=shared")
@Import(RecoveryServiceMailFailureTest.ThrowingMailConfig.class)
class RecoveryServiceMailFailureTest {

    @TestConfiguration static class ThrowingMailConfig {
        @Bean @Primary MailSender throwingMail() {
            return new MailSender() {
                public boolean available() { return true; }
                public void send(String to, String subject, String body) {
                    throw new IllegalStateException("smtp down");
                }
            };
        }
    }

    @Autowired RecoveryService recovery;
    @Autowired UserMapper users;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach void clean() {
        jdbc.update("DELETE FROM totp_recovery");
        jdbc.update("DELETE FROM user_totp");
        jdbc.update("DELETE FROM app_user");
        users.insert(new UserRow("u1","10001","a@corp.local","홍","admin","active",null));
        jdbc.update("INSERT INTO user_totp(user_id,secret_enc,enabled,last_step,created_at) VALUES('u1','X',1,0,'2026-06-19T00:00:00')");
    }

    @Test void requestSwallowsMailSendFailure() {
        assertThatCode(() -> recovery.request("10001")).doesNotThrowAnyException();
    }
}
