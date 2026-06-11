package com.worknote.auth;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

// server 모드 전용 인메모리 DB — local 테스트(file::memory:)와 분리해 AdminBootstrap countUsers==0 오염 방지
@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:sqlite:file:phase2mem?mode=memory&cache=shared",
    "worknote.mode=server",
    "worknote.admin-password=boot-pass-1"
})
@AutoConfigureMockMvc
class AuthFilterTest {
    @Autowired MockMvc mvc;
    @Autowired UserMapper users;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        // u-admin 제외 — Task 7 AdminBootstrap이 시드할 admin 보존 (현재는 무해)
        jdbc.update("DELETE FROM user_credential WHERE user_id <> 'u-admin'");
        jdbc.update("DELETE FROM app_user WHERE id <> 'u-admin'");
        users.insert(new UserRow("u1", "10001", null, "홍길동", "operator", "active", null));
        String salt = PasswordHasher.newSalt();
        users.insertCredential(new CredentialRow("u1", salt, PasswordHasher.hash("pw-1234", salt)));
    }

    private MockHttpSession login(String emp, String pw) throws Exception {
        MockHttpSession session = new MockHttpSession();
        mvc.perform(post("/api/auth/login").session(session).contentType(APPLICATION_JSON)
                .content("{\"emp\":\"" + emp + "\",\"password\":\"" + pw + "\"}"))
            .andExpect(status().isOk());
        return session;
    }

    @Test
    void unauthenticatedApiIs401() throws Exception {
        mvc.perform(get("/api/tree"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.error").exists());
        mvc.perform(get("/api/healthz")).andExpect(status().isUnauthorized());   // allowlist 정확 일치 회귀 고정
    }

    @Test
    void loginAndHealthAreAllowlisted() throws Exception {
        mvc.perform(get("/api/health")).andExpect(status().isOk());
        mvc.perform(post("/api/auth/login").contentType(APPLICATION_JSON)
                .content("{\"emp\":\"x\",\"password\":\"y\"}"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.error").exists());   // 401(자격 오류)이지 필터 차단이 아님
    }

    @Test
    void authenticatedSessionPassesAndMeWorks() throws Exception {
        MockHttpSession session = login("10001", "pw-1234");
        mvc.perform(get("/api/auth/me").session(session))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.emp").value("10001"));
        mvc.perform(get("/api/tree").session(session))
            .andExpect(status().isOk());
    }

    @Test
    void logoutThen401() throws Exception {
        MockHttpSession session = login("10001", "pw-1234");
        mvc.perform(post("/api/auth/logout").session(session)).andExpect(status().isNoContent());
        mvc.perform(get("/api/auth/me").session(session)).andExpect(status().isUnauthorized());
    }

    @Test
    void disabledUserSessionIsRejected() throws Exception {
        MockHttpSession session = login("10001", "pw-1234");
        jdbc.update("UPDATE app_user SET status = 'disabled' WHERE id = 'u1'");
        mvc.perform(get("/api/tree").session(session))
            .andExpect(status().isUnauthorized());   // 세션 살아있어도 비활성화 즉시 차단
    }
}
