package com.worknote.auth;

import com.worknote.auth.totp.*;
import org.junit.jupiter.api.*;
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

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:sqlite:file:memdb-2falogin?mode=memory&cache=shared",
    "worknote.totp.key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
})
@AutoConfigureMockMvc
class Totp2faLoginTest {
    @Autowired MockMvc mvc;
    @Autowired UserMapper users;
    @Autowired TotpService totp;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach void clean() {
        jdbc.update("DELETE FROM user_totp"); jdbc.update("DELETE FROM user_credential"); jdbc.update("DELETE FROM app_user");
        users.insert(new UserRow("u1","10001",null,"홍","operator","active",null));
        String salt = PasswordHasher.newSalt();
        users.insertCredential(new CredentialRow("u1", salt, PasswordHasher.hash("pw-1234", salt)));
    }

    @Test void loginWithoutTotp_isFullyAuthenticated() throws Exception {
        mvc.perform(post("/api/auth/login").contentType(APPLICATION_JSON)
                .content("{\"emp\":\"10001\",\"password\":\"pw-1234\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.emp").value("10001"));
    }

    @Test void loginWithTotp_returns2faRequired_andBlocksApiUntilVerified() throws Exception {
        enable2fa("u1","10001");
        MockHttpSession s = new MockHttpSession();
        mvc.perform(post("/api/auth/login").session(s).contentType(APPLICATION_JSON)
                .content("{\"emp\":\"10001\",\"password\":\"pw-1234\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("2fa_required"));
        // 부분 인증 — 일반 API는 401 (server 모드 필터). 단 이 테스트는 local 모드이므로 필터 없음 →
        // 부분 인증 차단은 AuthFilterTest(별도, server 프로퍼티)에서 검증. 여기선 verify 흐름만.
    }

    @Test void verifyWithValidCode_completesAuth() throws Exception {
        // enable2fa를 한 step 전 코드로 confirm → 현재 step 코드를 verify에서 사용 가능
        long epoch = Instant.now().getEpochSecond();
        long prevStepEpoch = (epoch / Totp.PERIOD - 1) * Totp.PERIOD;  // 현재 step의 직전 step
        totp.setup("u1", "10001");
        String secret = totp.currentSecretForTest("u1");
        totp.confirm("u1", Totp.codeAt(secret, prevStepEpoch));  // 직전 step으로 confirm (현재 step 사용 가능)
        String code = Totp.codeAt(secret, epoch);  // 현재 step 코드
        MockHttpSession s = new MockHttpSession();
        mvc.perform(post("/api/auth/login").session(s).contentType(APPLICATION_JSON)
                .content("{\"emp\":\"10001\",\"password\":\"pw-1234\"}"));
        mvc.perform(post("/api/auth/2fa/verify").session(s).contentType(APPLICATION_JSON)
                .content("{\"code\":\"" + code + "\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.emp").value("10001"));
        assertThat(s.getAttribute(AuthController.SESSION_CRED)).isNotNull();
    }

    @Test void verifyWrongCode_is401() throws Exception {
        enable2fa("u1","10001");
        MockHttpSession s = new MockHttpSession();
        mvc.perform(post("/api/auth/login").session(s).contentType(APPLICATION_JSON)
                .content("{\"emp\":\"10001\",\"password\":\"pw-1234\"}"));
        mvc.perform(post("/api/auth/2fa/verify").session(s).contentType(APPLICATION_JSON)
                .content("{\"code\":\"000000\"}"))
            .andExpect(status().isUnauthorized());
    }

    private void enable2fa(String id, String emp) {
        totp.setup(id, emp);
        String secret = totp.currentSecretForTest(id);
        totp.confirm(id, Totp.codeAt(secret, Instant.now().getEpochSecond()));
    }
}
