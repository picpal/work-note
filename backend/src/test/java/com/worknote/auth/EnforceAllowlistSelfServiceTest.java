package com.worknote.auth;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * T2 — 유예 만료 admin의 자기관리 경로. email은 TOTP setup의 선행 조건이라 update-profile이 막히면
 * 등록 자체가 불가능하고, change-password가 막히면 탈취 의심 비밀번호를 바꿀 수 없다.
 */
@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:sqlite:file:memdb-enforce-self?mode=memory&cache=shared",
    "worknote.mode=server",
    "worknote.admin-password=seed-admin-pw",
    "worknote.totp.key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
})
@AutoConfigureMockMvc
class EnforceAllowlistSelfServiceTest {
    @Autowired MockMvc mvc;
    @Autowired UserMapper users;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM audit_log");
        jdbc.update("DELETE FROM user_totp");
        jdbc.update("DELETE FROM user_credential");
        jdbc.update("DELETE FROM app_user");
        users.insert(new UserRow("a1", "admin01", null, "관리", "admin", "active", null));
        String salt = PasswordHasher.newSalt();
        users.insertCredential(new CredentialRow("a1", salt, PasswordHasher.hash("pw-current-1", salt)));
        jdbc.update("UPDATE app_user SET totp_grace_start = '2026-06-01T00:00:00' WHERE id = 'a1'");
    }

    @Test
    void graceExpiredAdminCanUpdateOwnProfile() throws Exception {
        MockHttpSession s = login();
        mvc.perform(post("/api/auth/update-profile").session(s).contentType(APPLICATION_JSON)
                .content("{\"name\":\"관리\",\"email\":\"admin@corp.local\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.email").value("admin@corp.local"));
    }

    @Test
    void graceExpiredAdminCanChangeOwnPassword() throws Exception {
        MockHttpSession s = login();
        mvc.perform(post("/api/auth/change-password").session(s).contentType(APPLICATION_JSON)
                .content("{\"currentPassword\":\"pw-current-1\",\"newPassword\":\"pw-brand-new-1\"}"))
            .andExpect(status().isNoContent());
    }

    @Test
    void graceExpiredAdminIsStillBlockedElsewhere() throws Exception {
        MockHttpSession s = login();
        mvc.perform(get("/api/admin/users").session(s))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.error").value("2fa_enrollment_required"));
        mvc.perform(get("/api/tree").session(s)).andExpect(status().isForbidden());
    }

    private MockHttpSession login() throws Exception {
        MockHttpSession s = new MockHttpSession();
        mvc.perform(post("/api/auth/login").session(s).contentType(APPLICATION_JSON)
                .content("{\"emp\":\"admin01\",\"password\":\"pw-current-1\"}"))
            .andExpect(status().isOk());
        return s;
    }
}
