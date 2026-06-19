package com.worknote.auth;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:sqlite:file:memdb-enforce?mode=memory&cache=shared",
    "worknote.mode=server",
    "worknote.admin-password=seed-admin-pw",
    "worknote.totp.key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
})
@AutoConfigureMockMvc
class Totp2faEnforceTest {
    @Autowired MockMvc mvc; @Autowired UserMapper users; @Autowired JdbcTemplate jdbc;

    @BeforeEach void clean() {
        jdbc.update("DELETE FROM user_totp"); jdbc.update("DELETE FROM user_credential"); jdbc.update("DELETE FROM app_user");
        users.insert(new UserRow("a1","admin01",null,"관리","admin","active",null));
        String salt = PasswordHasher.newSalt();
        users.insertCredential(new CredentialRow("a1", salt, PasswordHasher.hash("pw-1234", salt)));
    }

    @Test void adminWithoutTotp_meReportsEnforced_andSetsGraceStart() throws Exception {
        MockHttpSession s = login("admin01","pw-1234");
        mvc.perform(get("/api/auth/me").session(s))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.totp.enabled").value(false))
            .andExpect(jsonPath("$.totp.enforced").value(true));
        // grace_start가 기록됨
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject(
            "SELECT totp_grace_start FROM app_user WHERE id='a1'", String.class)).isNotNull();
    }

    @Test void adminGraceExpired_blocksGeneralApi_butAllowsMeAndSetup() throws Exception {
        // grace_start를 8일 전으로 강제
        jdbc.update("UPDATE app_user SET totp_grace_start='2026-06-01T00:00:00' WHERE id='a1'");
        MockHttpSession s = login("admin01","pw-1234");
        // /me 는 허용
        mvc.perform(get("/api/auth/me").session(s)).andExpect(status().isOk());
        // 일반 API(예: /api/admin/users)는 차단 — 강제 등록 유도
        mvc.perform(get("/api/admin/users").session(s)).andExpect(status().isForbidden());
    }

    private MockHttpSession login(String emp, String pw) throws Exception {
        MockHttpSession s = new MockHttpSession();
        mvc.perform(post("/api/auth/login").session(s).contentType(APPLICATION_JSON)
            .content("{\"emp\":\""+emp+"\",\"password\":\""+pw+"\"}")).andExpect(status().isOk());
        return s;
    }
}
