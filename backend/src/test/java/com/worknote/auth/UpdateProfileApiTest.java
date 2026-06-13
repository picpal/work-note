package com.worknote.auth;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 본인 프로필 수정(POST /api/auth/update-profile) — server 모드. ChangePasswordApiTest 하네스 따름. */
@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:sqlite:file:phase2mem?mode=memory&cache=shared",
    "worknote.mode=server",
    "worknote.admin-password=boot-pass-1"
})
@AutoConfigureMockMvc
class UpdateProfileApiTest {
    @Autowired MockMvc mvc;
    @Autowired UserMapper users;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM audit_log");
        jdbc.update("DELETE FROM user_credential WHERE user_id <> 'u-admin'");
        jdbc.update("DELETE FROM app_user WHERE id <> 'u-admin'");
        users.insert(new UserRow("u1", "10001", "old@corp.local", "옛이름", "operator", "active", null));
        String salt = PasswordHasher.newSalt();
        users.insertCredential(new CredentialRow("u1", salt, PasswordHasher.hash("pw-current", salt)));
    }

    private MockHttpSession login(String emp, String pw) throws Exception {
        MockHttpSession session = new MockHttpSession();
        mvc.perform(post("/api/auth/login").session(session).contentType(APPLICATION_JSON)
                .content("{\"emp\":\"" + emp + "\",\"password\":\"" + pw + "\"}"))
            .andExpect(status().isOk());
        return session;
    }

    private static String body(String name, String email) {
        return "{\"name\":\"" + name + "\",\"email\":\"" + email + "\"}";
    }

    // 1. 성공 → 200 + jsonPath name/email + DB 반영
    @Test
    void updateSucceedsReturnsMeAndPersists() throws Exception {
        MockHttpSession s = login("10001", "pw-current");
        mvc.perform(post("/api/auth/update-profile").session(s).contentType(APPLICATION_JSON)
                .content(body("새이름", "new@corp.local")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.name").value("새이름"))
            .andExpect(jsonPath("$.email").value("new@corp.local"));
        UserRow row = users.findById("u1");
        assertThat(row.name()).isEqualTo("새이름");
        assertThat(row.email()).isEqualTo("new@corp.local");
    }

    // 2. email "" → DB null (공백은 미지정으로 정규화)
    @Test
    void blankEmailNormalizedToNull() throws Exception {
        MockHttpSession s = login("10001", "pw-current");
        mvc.perform(post("/api/auth/update-profile").session(s).contentType(APPLICATION_JSON)
                .content(body("새이름", "")))
            .andExpect(status().isOk());
        UserRow row = users.findById("u1");
        assertThat(row.email()).isNull();
    }

    // 3. name "" → 400 (@NotBlank)
    @Test
    void blankNameIs400() throws Exception {
        MockHttpSession s = login("10001", "pw-current");
        mvc.perform(post("/api/auth/update-profile").session(s).contentType(APPLICATION_JSON)
                .content(body("", "new@corp.local")))
            .andExpect(status().isBadRequest());
    }

    // 4. 미인증(세션 없음) → 401 (AuthFilter가 컨트롤러 전에 차단)
    @Test
    void unauthenticatedIs401() throws Exception {
        mvc.perform(post("/api/auth/update-profile").contentType(APPLICATION_JSON)
                .content(body("새이름", "new@corp.local")))
            .andExpect(status().isUnauthorized());
    }

    // 5. 감사 기록 auth.profile.update 1건
    @Test
    void updateIsAudited() throws Exception {
        MockHttpSession s = login("10001", "pw-current");
        mvc.perform(post("/api/auth/update-profile").session(s).contentType(APPLICATION_JSON)
                .content(body("새이름", "new@corp.local")))
            .andExpect(status().isOk());
        Integer n = jdbc.queryForObject(
            "SELECT COUNT(*) FROM audit_log WHERE act = 'auth.profile.update'", Integer.class);
        assertThat(n).isEqualTo(1);
    }
}
