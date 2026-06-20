package com.worknote.auth.totp;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:sqlite:file:memdb-totpsvc?mode=memory&cache=shared",
    "worknote.totp.key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="   // Base64 32B (테스트용)
})
class TotpServiceTest {
    @Autowired TotpService svc;
    @Autowired TotpMapper mapper;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach void clean() {
        jdbc.update("DELETE FROM user_totp");
        jdbc.update("DELETE FROM app_user");
        jdbc.update("INSERT INTO app_user(id,emp,name,role_id,status) VALUES('u1','10001','홍','admin','active')");
    }

    @Test void setupCreatesDisabledRowAndReturnsUri() {
        String uri = svc.setup("u1", "10001");
        assertThat(uri).startsWith("otpauth://totp/work-note:10001?secret=");
        assertThat(svc.isEnabled("u1")).isFalse();        // 확인 전엔 비활성
        assertThat(mapper.find("u1").secretEnc()).isNotBlank();
    }

    @Test void confirmWithValidCodeEnables() {
        svc.setup("u1", "10001");
        String secret = svc.currentSecretForTest("u1");   // 테스트 헬퍼 — 복호화한 base32
        String code = Totp.codeAt(secret, java.time.Instant.now().getEpochSecond());
        assertThat(svc.confirm("u1", code)).isTrue();
        assertThat(svc.isEnabled("u1")).isTrue();
    }

    @Test void confirmWrongCodeKeepsDisabled() {
        svc.setup("u1", "10001");
        assertThat(svc.confirm("u1", "000000")).isFalse();
        assertThat(svc.isEnabled("u1")).isFalse();
    }

    @Test void verifyLoginAdvancesLastStepAndBlocksReplay() {
        svc.setup("u1", "10001");
        String secret = svc.currentSecretForTest("u1");
        long epoch = java.time.Instant.now().getEpochSecond();
        String code = Totp.codeAt(secret, epoch);
        svc.confirm("u1", code);
        // 같은 윈도 코드로 로그인 — confirm이 이미 그 step을 소비했으면 재생 거부
        // (confirm/verifyLogin 모두 last_step을 갱신해야 함)
        assertThat(svc.verifyLogin("u1", code)).isFalse();   // 재생
    }

    @Test void resetRemovesRow() {
        svc.setup("u1", "10001");
        svc.reset("u1");
        assertThat(mapper.find("u1")).isNull();
    }
}
