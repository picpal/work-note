package com.worknote.admin;

import com.worknote.auth.CredentialRow;
import com.worknote.auth.PasswordHasher;
import com.worknote.auth.UserMapper;
import com.worknote.auth.UserRow;
import com.worknote.auth.totp.Totp;
import com.worknote.auth.totp.TotpService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:sqlite:file:memdb-adminreset?mode=memory&cache=shared",
    "worknote.mode=server",
    "worknote.admin-password=boot-pass-2fa",
    "worknote.totp.key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
})
@AutoConfigureMockMvc
class AdminUser2faResetTest {

    @Autowired MockMvc mvc;
    @Autowired UserMapper users;
    @Autowired TotpService totp;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM audit_log");
        jdbc.update("DELETE FROM user_totp");
        jdbc.update("DELETE FROM user_credential WHERE user_id <> 'u-admin'");
        jdbc.update("DELETE FROM app_user WHERE id <> 'u-admin'");
        jdbc.update("UPDATE app_user SET role_id = 'admin', status = 'active' WHERE id = 'u-admin'");

        // u2: TOTP 등록 대상
        users.insert(new UserRow("u2", "20002", "b@corp.local", "김", "operator", "active", null));
        String salt2 = PasswordHasher.newSalt();
        users.insertCredential(new CredentialRow("u2", salt2, PasswordHasher.hash("pw-1234", salt2)));

        // set up TOTP for u2 and confirm it
        totp.setup("u2", "20002");
        totp.confirm("u2", Totp.codeAt(totp.currentSecretForTest("u2"), Instant.now().getEpochSecond()));

        // u3: TOTP 없는 일반 사용자 — nonAdmin 가드 테스트용
        users.insert(new UserRow("u3", "30003", null, "박", "operator", "active", null));
        String salt3 = PasswordHasher.newSalt();
        users.insertCredential(new CredentialRow("u3", salt3, PasswordHasher.hash("pw-1234", salt3)));
    }

    private MockHttpSession adminLogin() throws Exception {
        MockHttpSession session = new MockHttpSession();
        mvc.perform(post("/api/auth/login").session(session)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"emp\":\"admin\",\"password\":\"boot-pass-2fa\"}"))
            .andExpect(status().isOk());
        return session;
    }

    @Test
    void resetRemovesUserTotp() throws Exception {
        assertThat(totp.isEnabled("u2")).isTrue();

        MockHttpSession admin = adminLogin();
        mvc.perform(post("/api/admin/users/u2/2fa/reset").session(admin))
            .andExpect(status().isNoContent());

        assertThat(totp.isEnabled("u2")).isFalse();
    }

    @Test
    void resetAuditsEvent() throws Exception {
        MockHttpSession admin = adminLogin();
        mvc.perform(post("/api/admin/users/u2/2fa/reset").session(admin))
            .andExpect(status().isNoContent());

        // 감사 target은 사번(emp)으로 기록 — 다른 admin 액션과 일관
        assertThat(jdbc.queryForObject(
            "SELECT COUNT(*) FROM audit_log WHERE act = '2fa.admin.reset' AND target = '20002'",
            Integer.class)).isEqualTo(1);
    }

    @Test
    void listIncludesTotpEnabled() throws Exception {
        MockHttpSession admin = adminLogin();
        mvc.perform(get("/api/admin/users").session(admin))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[?(@.emp=='20002')].totpEnabled").value(true));
    }

    @Test
    void nonAdmin_reset_403() throws Exception {
        // 일반 사용자(operator, TOTP 없음) 로그인 후 관리자 전용 엔드포인트 → 403
        MockHttpSession op = new MockHttpSession();
        mvc.perform(post("/api/auth/login").session(op)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"emp\":\"30003\",\"password\":\"pw-1234\"}"))
            .andExpect(status().isOk());
        mvc.perform(post("/api/admin/users/u2/2fa/reset").session(op))
            .andExpect(status().isForbidden());
    }
}
