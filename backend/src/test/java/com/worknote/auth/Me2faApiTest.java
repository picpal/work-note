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
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:sqlite:file:memdb-me2fa?mode=memory&cache=shared",
    "worknote.mode=server","worknote.admin-password=x",
    "worknote.totp.key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
})
@AutoConfigureMockMvc
class Me2faApiTest {
    @Autowired MockMvc mvc;
    @Autowired UserMapper users;
    @Autowired TotpService totp;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach void clean() {
        jdbc.update("DELETE FROM user_totp");
        jdbc.update("DELETE FROM user_credential");
        jdbc.update("DELETE FROM app_user");
        users.insert(new UserRow("u1","10001","a@corp.local","홍","operator","active",null));
        String salt = PasswordHasher.newSalt();
        users.insertCredential(new CredentialRow("u1", salt, PasswordHasher.hash("pw-1234", salt)));
    }

    @Test void setupRequiresEmail_422WhenMissing() throws Exception {
        users.update(new UserRow("u1","10001",null,"홍","operator","active",null));   // 이메일 제거
        MockHttpSession s = login();
        mvc.perform(post("/api/me/2fa/setup").session(s)).andExpect(status().isUnprocessableEntity());
    }

    @Test void setupThenConfirmEnables() throws Exception {
        MockHttpSession s = login();
        mvc.perform(post("/api/me/2fa/setup").session(s))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.otpauthUri").exists());
        String secret = totp.currentSecretForTest("u1");
        String code = Totp.codeAt(secret, Instant.now().getEpochSecond());
        mvc.perform(post("/api/me/2fa/confirm").session(s).contentType(APPLICATION_JSON)
                .content("{\"code\":\""+code+"\"}"))
            .andExpect(status().isNoContent());
    }

    @Test void qrReturnsPng() throws Exception {
        MockHttpSession s = login();
        mvc.perform(post("/api/me/2fa/setup").session(s));
        mvc.perform(get("/api/me/2fa/qr").session(s))
            .andExpect(status().isOk())
            .andExpect(content().contentType("image/png"));
    }

    @Test void deleteDisabledByEnforcedAdmin() throws Exception {
        // admin 사용자가 2FA 활성화 후 DELETE 시도 → 403
        jdbc.update("DELETE FROM user_totp");
        jdbc.update("DELETE FROM user_credential");
        jdbc.update("DELETE FROM app_user");
        users.insert(new UserRow("a1","admin01","a@corp.local","관리","admin","active",null));
        String salt = PasswordHasher.newSalt();
        users.insertCredential(new CredentialRow("a1", salt, PasswordHasher.hash("pw-1234", salt)));
        // enable 2FA for admin — confirm with step N-1 (now-30s), so verifyLogin can use step N (now)
        totp.setup("a1", "admin01");
        String secret = totp.currentSecretForTest("a1");
        long now = Instant.now().getEpochSecond();
        // Confirm at a past step to leave room for verify (step N-1 so step N is available for login verify)
        totp.confirm("a1", Totp.codeAt(secret, now - 30));

        // login → 2fa verify → full session
        MockHttpSession s = new MockHttpSession();
        mvc.perform(post("/api/auth/login").session(s).contentType(APPLICATION_JSON)
            .content("{\"emp\":\"admin01\",\"password\":\"pw-1234\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("2fa_required"));
        // verify with code at current time (step N, which is > step N-1 used in confirm)
        String code2 = Totp.codeAt(totp.currentSecretForTest("a1"), now);
        mvc.perform(post("/api/auth/2fa/verify").session(s).contentType(APPLICATION_JSON)
            .content("{\"code\":\""+code2+"\"}"))
            .andExpect(status().isOk());

        // now DELETE /api/me/2fa should be 403 for enforced admin
        mvc.perform(delete("/api/me/2fa").session(s))
            .andExpect(status().isForbidden());
    }

    private MockHttpSession login() throws Exception {
        MockHttpSession s = new MockHttpSession();
        mvc.perform(post("/api/auth/login").session(s).contentType(APPLICATION_JSON)
            .content("{\"emp\":\"10001\",\"password\":\"pw-1234\"}")).andExpect(status().isOk());
        return s;
    }
}
