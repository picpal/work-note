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

/** 본인 비밀번호 변경(POST /api/auth/change-password) — server 모드. AuthControllerTest 컨벤션(u-admin 보존) 따름. */
@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:sqlite:file:phase2mem?mode=memory&cache=shared",
    "worknote.mode=server",
    "worknote.admin-password=boot-pass-1"
})
@AutoConfigureMockMvc
class ChangePasswordApiTest {
    @Autowired MockMvc mvc;
    @Autowired UserMapper users;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM audit_log");
        jdbc.update("DELETE FROM user_credential WHERE user_id <> 'u-admin'");
        jdbc.update("DELETE FROM app_user WHERE id <> 'u-admin'");
        users.insert(new UserRow("u1", "10001", null, "홍길동", "operator", "active", null));
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

    private static String body(String cur, String next) {
        return "{\"currentPassword\":\"" + cur + "\",\"newPassword\":\"" + next + "\"}";
    }

    // 1. 성공 → 204 + 본인 세션 유지(같은 세션 me 200) + 새 비번 로그인 가능
    @Test
    void changeSucceedsKeepsOwnSession() throws Exception {
        MockHttpSession s = login("10001", "pw-current");
        mvc.perform(post("/api/auth/change-password").session(s).contentType(APPLICATION_JSON)
                .content(body("pw-current", "new-pw-9999")))
            .andExpect(status().isNoContent());
        mvc.perform(get("/api/auth/me").session(s)).andExpect(status().isOk());           // 본인 세션 유지
        mvc.perform(post("/api/auth/login").session(new MockHttpSession()).contentType(APPLICATION_JSON)
                .content("{\"emp\":\"10001\",\"password\":\"new-pw-9999\"}"))
            .andExpect(status().isOk());                                                   // 새 비번으로 신규 로그인
    }

    // 2. 다른 기기(옛 salt) 세션은 변경 후 무효 → 401
    @Test
    void otherSessionInvalidatedAfterChange() throws Exception {
        MockHttpSession other = login("10001", "pw-current");
        MockHttpSession s = login("10001", "pw-current");
        mvc.perform(post("/api/auth/change-password").session(s).contentType(APPLICATION_JSON)
                .content(body("pw-current", "new-pw-9999")))
            .andExpect(status().isNoContent());
        mvc.perform(get("/api/auth/me").session(other)).andExpect(status().isUnauthorized());
    }

    // 3. 현재 비번 틀림 → 422 (401 아님 — 세션 유지)
    @Test
    void wrongCurrentPasswordIs422NotUnauthorized() throws Exception {
        MockHttpSession s = login("10001", "pw-current");
        mvc.perform(post("/api/auth/change-password").session(s).contentType(APPLICATION_JSON)
                .content(body("WRONG", "new-pw-9999")))
            .andExpect(status().isUnprocessableEntity());
        mvc.perform(get("/api/auth/me").session(s)).andExpect(status().isOk());            // 세션 유효 유지
    }

    // 4. 새 비번 10자 미만 → 422
    @Test
    void shortNewPasswordIs422() throws Exception {
        MockHttpSession s = login("10001", "pw-current");
        mvc.perform(post("/api/auth/change-password").session(s).contentType(APPLICATION_JSON)
                .content(body("pw-current", "short")))
            .andExpect(status().isUnprocessableEntity());
    }

    // 5. 새 비번 == 현재 비번 → 422
    @Test
    void sameAsCurrentIs422() throws Exception {
        MockHttpSession s = login("10001", "pw-current");
        mvc.perform(post("/api/auth/change-password").session(s).contentType(APPLICATION_JSON)
                .content(body("pw-current", "pw-current")))
            .andExpect(status().isUnprocessableEntity());
    }

    // 6. 빈 본문 → 400 (@NotBlank) — 단, 세션 있어 필터 통과 후 검증
    @Test
    void blankBodyIs400() throws Exception {
        MockHttpSession s = login("10001", "pw-current");
        mvc.perform(post("/api/auth/change-password").session(s).contentType(APPLICATION_JSON)
                .content(body("", "")))
            .andExpect(status().isBadRequest());
    }

    // 7. 미인증(세션 없음) → 401 (AuthFilter가 컨트롤러 전에 차단)
    @Test
    void unauthenticatedIs401() throws Exception {
        mvc.perform(post("/api/auth/change-password").contentType(APPLICATION_JSON)
                .content(body("pw-current", "new-pw-9999")))
            .andExpect(status().isUnauthorized());
    }

    // 8. 감사 기록 auth.password.change 1건
    @Test
    void changeIsAudited() throws Exception {
        MockHttpSession s = login("10001", "pw-current");
        mvc.perform(post("/api/auth/change-password").session(s).contentType(APPLICATION_JSON)
                .content(body("pw-current", "new-pw-9999")))
            .andExpect(status().isNoContent());
        Integer n = jdbc.queryForObject(
            "SELECT COUNT(*) FROM audit_log WHERE act = 'auth.password.change'", Integer.class);
        assertThat(n).isEqualTo(1);
    }
}
