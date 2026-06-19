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

    @Test void requestAlwaysReturns204_evenUnknownEmp() throws Exception {
        mvc.perform(post("/api/auth/2fa/recover/request").contentType(APPLICATION_JSON)
            .content("{\"emp\":\"99999\"}")).andExpect(status().isNoContent());   // 열거 방지
        mvc.perform(post("/api/auth/2fa/recover/request").contentType(APPLICATION_JSON)
            .content("{\"emp\":\"10001\"}")).andExpect(status().isNoContent());
    }

    @Test void verifyValidCodeLogsInAndDisablesTotp() throws Exception {
        mvc.perform(post("/api/auth/2fa/recover/request").contentType(APPLICATION_JSON)
            .content("{\"emp\":\"10001\"}"));
        String code = BODY.get().replaceAll("[^0-9]","").substring(0,8);
        MockHttpSession s = new MockHttpSession();
        mvc.perform(post("/api/auth/2fa/recover/verify").session(s).contentType(APPLICATION_JSON)
            .content("{\"emp\":\"10001\",\"code\":\""+code+"\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.emp").value("10001"))
            .andExpect(jsonPath("$.totp.enabled").value(false));   // 복구 = 2FA 폐기(재등록 강제)
    }
}
