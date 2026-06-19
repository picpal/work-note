package com.worknote.auth.totp;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = "spring.datasource.url=jdbc:sqlite:file:memdb-totpmap?mode=memory&cache=shared")
class TotpMapperTest {
    @Autowired TotpMapper totp;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach void clean() {
        jdbc.update("DELETE FROM user_totp");
        jdbc.update("DELETE FROM totp_recovery");
        jdbc.update("DELETE FROM app_user");
        jdbc.update("INSERT INTO app_user(id,emp,name,role_id,status) VALUES('u1','10001','홍','admin','active')");
    }

    @Test void insertAndFind() {
        totp.insert(new TotpRow("u1", "ENC", 0, null, 0, "2026-06-19T00:00:00"));
        TotpRow r = totp.find("u1");
        assertThat(r.secretEnc()).isEqualTo("ENC");
        assertThat(r.enabled()).isZero();
    }

    @Test void enableAndStampStep() {
        totp.insert(new TotpRow("u1", "ENC", 0, null, 0, "2026-06-19T00:00:00"));
        totp.enable("u1", "2026-06-19T01:00:00");
        totp.updateLastStep("u1", 12345L);
        TotpRow r = totp.find("u1");
        assertThat(r.enabled()).isEqualTo(1);
        assertThat(r.confirmedAt()).isEqualTo("2026-06-19T01:00:00");
        assertThat(r.lastStep()).isEqualTo(12345L);
    }

    @Test void deleteRemoves() {
        totp.insert(new TotpRow("u1", "ENC", 0, null, 0, "2026-06-19T00:00:00"));
        totp.delete("u1");
        assertThat(totp.find("u1")).isNull();
    }

    @Test void recoveryInsertFindLatestMarkUsed() {
        totp.insertRecovery(new RecoveryRow("rc1","u1","s","h","2026-06-19T00:10:00",0,"2026-06-19T00:00:00"));
        RecoveryRow latest = totp.findLatestRecovery("u1");
        assertThat(latest.id()).isEqualTo("rc1");
        totp.markRecoveryUsed("rc1");
        assertThat(totp.findLatestRecovery("u1").used()).isEqualTo(1);
        totp.invalidateRecovery("u1");   // 미사용분 전부 used=1
    }
}
