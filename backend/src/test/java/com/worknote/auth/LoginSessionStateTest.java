package com.worknote.auth;

import com.worknote.auth.totp.Totp;
import com.worknote.auth.totp.TotpService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * T1 — 로그인 전이 시 인증 상태 초기화. changeSessionId()는 id만 바꾸고 내용을 유지하므로
 * 같은 세션에서 계정을 바꿔 로그인하면 이전 로그인의 pending/cred 플래그가 잔류한다(가용성 결함).
 */
@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:sqlite:file:memdb-loginstate?mode=memory&cache=shared",
    "worknote.mode=server",
    "worknote.admin-password=seed-admin-pw",
    "worknote.totp.key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
})
@AutoConfigureMockMvc
class LoginSessionStateTest {
    @Autowired MockMvc mvc;
    @Autowired UserMapper users;
    @Autowired TotpService totp;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM audit_log");
        jdbc.update("DELETE FROM user_totp");
        jdbc.update("DELETE FROM user_credential");
        jdbc.update("DELETE FROM app_user");
        seed("u1", "10001", "홍길동");     // 2FA 미사용
        seed("u2", "10002", "김철수");     // 2FA 사용 (테스트에서 enable)
    }

    private void seed(String id, String emp, String name) {
        users.insert(new UserRow(id, emp, null, name, "operator", "active", null));
        String salt = PasswordHasher.newSalt();
        users.insertCredential(new CredentialRow(id, salt, PasswordHasher.hash("pw-1234", salt)));
    }

    /** 부분 인증 잔류 → 비-2FA 계정 로그인 시 pending 플래그 제거. */
    @Test
    void nonTotpLoginClearsStalePendingFlag() throws Exception {
        enable2fa("u2", "10002");
        MockHttpSession s = new MockHttpSession();
        mvc.perform(post("/api/auth/login").session(s).contentType(APPLICATION_JSON)
                .content("{\"emp\":\"10002\",\"password\":\"pw-1234\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("2fa_required"));

        // 같은 세션으로 2FA 미사용 계정 로그인 → 완전 인증
        mvc.perform(post("/api/auth/login").session(s).contentType(APPLICATION_JSON)
                .content("{\"emp\":\"10001\",\"password\":\"pw-1234\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.emp").value("10001"));
        assertThat(s.getAttribute(AuthController.SESSION_2FA_PENDING)).isNull();

        // 잔류 플래그가 없으므로 일반 API 통과
        mvc.perform(get("/api/tree").session(s)).andExpect(status().isOk());
        // pending이 아니므로 2FA 검증 진입 불가
        mvc.perform(post("/api/auth/2fa/verify").session(s).contentType(APPLICATION_JSON)
                .content("{\"code\":\"000000\"}"))
            .andExpect(status().isUnauthorized());
    }

    /** 완전 인증 세션에서 2FA 계정으로 재로그인 → 부분 인증 진입 시 이전 cred 제거. */
    @Test
    void pendingLoginClearsPreviousCredential() throws Exception {
        enable2fa("u2", "10002");
        MockHttpSession s = new MockHttpSession();
        mvc.perform(post("/api/auth/login").session(s).contentType(APPLICATION_JSON)
                .content("{\"emp\":\"10001\",\"password\":\"pw-1234\"}"))
            .andExpect(status().isOk());
        assertThat(s.getAttribute(AuthController.SESSION_CRED)).isNotNull();

        mvc.perform(post("/api/auth/login").session(s).contentType(APPLICATION_JSON)
                .content("{\"emp\":\"10002\",\"password\":\"pw-1234\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("2fa_required"));
        assertThat(s.getAttribute(AuthController.SESSION_CRED)).isNull();
    }

    private void enable2fa(String id, String emp) {
        totp.setup(id, emp);
        String secret = totp.currentSecretForTest(id);
        totp.confirm(id, Totp.codeAt(secret, Instant.now().getEpochSecond()));
    }
}
