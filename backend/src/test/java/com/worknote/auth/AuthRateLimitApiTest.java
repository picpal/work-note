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

import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:sqlite:file:memdb-ratelimit?mode=memory&cache=shared",
    "worknote.mode=server", "worknote.admin-password=boot-pass-x",
    "worknote.totp.key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
})
@AutoConfigureMockMvc
class AuthRateLimitApiTest {

    @Autowired MockMvc mvc;
    @Autowired UserMapper users;
    @Autowired TotpService totp;
    @Autowired JdbcTemplate jdbc;
    @Autowired AuthRateLimiter limiter;

    @BeforeEach void clean() {
        limiter.clearAll();
        jdbc.update("DELETE FROM totp_recovery");
        jdbc.update("DELETE FROM user_totp");
        jdbc.update("DELETE FROM user_credential");
        jdbc.update("DELETE FROM app_user");
        users.insert(new UserRow("u1", "10001", "a@corp.local", "홍", "operator", "active", null));
        String salt = PasswordHasher.newSalt();
        users.insertCredential(new CredentialRow("u1", salt, PasswordHasher.hash("pw-1234", salt)));
    }

    private void failLogin(String emp) throws Exception {
        mvc.perform(post("/api/auth/login").contentType(APPLICATION_JSON)
            .content("{\"emp\":\"" + emp + "\",\"password\":\"wrong\"}"))
            .andExpect(status().isUnauthorized());
    }

    @Test void sixthLoginAttempt_returns429_evenWithCorrectPassword() throws Exception {
        for (int i = 0; i < 5; i++) failLogin("10001");
        // 6번째 — 올바른 비밀번호여도 잠금 중이면 429 (잠금 중 크리덴셜 확인 자체를 차단)
        mvc.perform(post("/api/auth/login").contentType(APPLICATION_JSON)
            .content("{\"emp\":\"10001\",\"password\":\"pw-1234\"}"))
            .andExpect(status().isTooManyRequests())
            .andExpect(jsonPath("$.error").exists());
    }

    @Test void lockIsPerAccount_otherAccountStillWorks() throws Exception {
        users.insert(new UserRow("u2", "10002", null, "김", "operator", "active", null));
        String salt = PasswordHasher.newSalt();
        users.insertCredential(new CredentialRow("u2", salt, PasswordHasher.hash("pw-5678", salt)));
        for (int i = 0; i < 5; i++) failLogin("10001");
        mvc.perform(post("/api/auth/login").contentType(APPLICATION_JSON)
            .content("{\"emp\":\"10002\",\"password\":\"pw-5678\"}"))
            .andExpect(status().isOk());
    }

    @Test void successfulLoginClearsCounter() throws Exception {
        for (int i = 0; i < 4; i++) failLogin("10001");
        mvc.perform(post("/api/auth/login").contentType(APPLICATION_JSON)
            .content("{\"emp\":\"10001\",\"password\":\"pw-1234\"}"))
            .andExpect(status().isOk());
        // 성공으로 리셋 — 이후 1회 실패로 잠기지 않음
        failLogin("10001");
        mvc.perform(post("/api/auth/login").contentType(APPLICATION_JSON)
            .content("{\"emp\":\"10001\",\"password\":\"pw-1234\"}"))
            .andExpect(status().isOk());
    }

    @Test void fiveWrongRecoverCodes_locksRecoverVerify() throws Exception {
        // 복구 검증도 시도 제한 대상 — pending 세션에서 잘못된 코드 5회 후 6회차 429
        totp.setup("u1", "10001");
        totp.confirm("u1", Totp.codeAt(totp.currentSecretForTest("u1"),
            java.time.Instant.now().getEpochSecond()));
        MockHttpSession s = new MockHttpSession();
        mvc.perform(post("/api/auth/login").session(s).contentType(APPLICATION_JSON)
            .content("{\"emp\":\"10001\",\"password\":\"pw-1234\"}"))
            .andExpect(jsonPath("$.status").value("2fa_required"));
        for (int i = 0; i < 5; i++) {
            mvc.perform(post("/api/auth/2fa/recover/verify").session(s).contentType(APPLICATION_JSON)
                .content("{\"emp\":\"10001\",\"code\":\"WRONGWRONG12\"}"))
                .andExpect(status().isUnauthorized());
        }
        // 6번째 — 잠금 (recover 스코프)
        mvc.perform(post("/api/auth/2fa/recover/verify").session(s).contentType(APPLICATION_JSON)
            .content("{\"emp\":\"10001\",\"code\":\"WRONGWRONG12\"}"))
            .andExpect(status().isTooManyRequests());
    }

    @Test void fiveWrong2faCodes_locksVerify() throws Exception {
        totp.setup("u1", "10001");
        totp.confirm("u1", Totp.codeAt(totp.currentSecretForTest("u1"),
            java.time.Instant.now().getEpochSecond()));
        MockHttpSession s = new MockHttpSession();
        mvc.perform(post("/api/auth/login").session(s).contentType(APPLICATION_JSON)
            .content("{\"emp\":\"10001\",\"password\":\"pw-1234\"}"))
            .andExpect(jsonPath("$.status").value("2fa_required"));
        for (int i = 0; i < 5; i++) {
            mvc.perform(post("/api/auth/2fa/verify").session(s).contentType(APPLICATION_JSON)
                .content("{\"code\":\"000000\"}"))
                .andExpect(status().isUnauthorized());
        }
        // 6번째 — 잠금 (올바른 코드여도 검증 시도 자체가 차단돼야 함)
        String valid = Totp.codeAt(totp.currentSecretForTest("u1"),
            java.time.Instant.now().getEpochSecond());
        mvc.perform(post("/api/auth/2fa/verify").session(s).contentType(APPLICATION_JSON)
            .content("{\"code\":\"" + valid + "\"}"))
            .andExpect(status().isTooManyRequests());
    }
}
