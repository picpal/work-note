package com.worknote.auth;

import com.worknote.auth.totp.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.*;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import java.util.concurrent.atomic.AtomicReference;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:sqlite:file:memdb-recapi?mode=memory&cache=shared",
    "worknote.mode=server","worknote.admin-password=x",
    "worknote.totp.key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
})
@AutoConfigureMockMvc
@Import(Totp2faRecoverApiTest.FakeMail.class)
class Totp2faRecoverApiTest {
    static final AtomicReference<String> BODY = new AtomicReference<>();

    @TestConfiguration static class FakeMail {
        @Bean @Primary MailSender m() {
            return new MailSender() {
                public boolean available() { return true; }
                public void send(String t, String s, String b) { BODY.set(b); }
            };
        }
    }

    @Autowired MockMvc mvc;
    @Autowired UserMapper users;
    @Autowired TotpService totp;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach void clean() {
        BODY.set(null);
        jdbc.update("DELETE FROM totp_recovery");
        jdbc.update("DELETE FROM user_totp");
        jdbc.update("DELETE FROM user_credential");
        jdbc.update("DELETE FROM app_user");
        users.insert(new UserRow("u1","10001","a@corp.local","홍","operator","active",null));
        String salt = PasswordHasher.newSalt();
        users.insertCredential(new CredentialRow("u1", salt, PasswordHasher.hash("pw-1234", salt)));
        totp.setup("u1","10001");
        totp.confirm("u1", Totp.codeAt(totp.currentSecretForTest("u1"), java.time.Instant.now().getEpochSecond()));
    }

    /** 복구 플로우 진입 헬퍼 — 비밀번호 로그인으로 pending 세션 생성. */
    private MockHttpSession pendingSession() throws Exception {
        MockHttpSession s = new MockHttpSession();
        mvc.perform(post("/api/auth/login").session(s).contentType(APPLICATION_JSON)
            .content("{\"emp\":\"10001\",\"password\":\"pw-1234\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("2fa_required"));
        return s;
    }

    private String mailedCode() {
        return BODY.get().replaceAll("(?s).*복구 코드: (\\S+).*", "$1");
    }

    @Test void requestWithoutPendingSession_returns401() throws Exception {
        mvc.perform(post("/api/auth/2fa/recover/request").contentType(APPLICATION_JSON)
            .content("{\"emp\":\"10001\"}")).andExpect(status().isUnauthorized());
    }

    @Test void verifyWithoutPendingSession_returns401() throws Exception {
        mvc.perform(post("/api/auth/2fa/recover/verify").contentType(APPLICATION_JSON)
            .content("{\"emp\":\"10001\",\"code\":\"ABCD2345EFGH\"}"))
            .andExpect(status().isUnauthorized());
    }

    @Test void requestForDifferentEmp_returns401_uniformMessage() throws Exception {
        MockHttpSession s = pendingSession();
        // pending은 10001 — 다른 사번으로 요청하면 세션없음 케이스와 동일한 401 (열거 차단)
        mvc.perform(post("/api/auth/2fa/recover/request").session(s).contentType(APPLICATION_JSON)
            .content("{\"emp\":\"99999\"}")).andExpect(status().isUnauthorized());
    }

    @Test void recoverFlow_fromPendingSession_succeedsAndDisablesTotp() throws Exception {
        MockHttpSession s = pendingSession();
        mvc.perform(post("/api/auth/2fa/recover/request").session(s).contentType(APPLICATION_JSON)
            .content("{\"emp\":\"10001\"}")).andExpect(status().isNoContent());
        mvc.perform(post("/api/auth/2fa/recover/verify").session(s).contentType(APPLICATION_JSON)
            .content("{\"emp\":\"10001\",\"code\":\"" + mailedCode() + "\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.emp").value("10001"))
            .andExpect(jsonPath("$.totp.enabled").value(false));   // 복구 = 2FA 폐기(재등록 강제)
        // 승격 후 보호 API 통과 — pending 마커가 제거됐어야 함
        mvc.perform(get("/api/auth/me").session(s))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.emp").value("10001"));
    }

    @Test void recoverRequest_stillSilentSkipsWhenNoEmail_returns204() throws Exception {
        // 이메일 없는 사용자 — pending까지 왔더라도 발송은 조용히 skip, 204 균등 응답 유지
        jdbc.update("UPDATE app_user SET email = NULL WHERE id = 'u1'");
        MockHttpSession s = pendingSession();
        mvc.perform(post("/api/auth/2fa/recover/request").session(s).contentType(APPLICATION_JSON)
            .content("{\"emp\":\"10001\"}")).andExpect(status().isNoContent());
        org.assertj.core.api.Assertions.assertThat(BODY.get()).isNull();
    }
}
