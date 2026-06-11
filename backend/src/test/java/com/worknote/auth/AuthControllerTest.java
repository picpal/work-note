package com.worknote.auth;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = "spring.datasource.url=jdbc:sqlite:file::memory:?cache=shared")
@AutoConfigureMockMvc
class AuthControllerTest {
    @Autowired MockMvc mvc;
    @Autowired UserMapper users;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM user_credential");
        jdbc.update("DELETE FROM app_user");
        users.insert(new UserRow("u1", "10001", null, "홍길동", "operator", "active", null));
        String salt = PasswordHasher.newSalt();
        users.insertCredential(new CredentialRow("u1", salt, PasswordHasher.hash("pw-1234", salt)));
    }

    @Test
    void loginSetsSessionAndReturnsMe() throws Exception {
        MockHttpSession session = new MockHttpSession();
        mvc.perform(post("/api/auth/login").session(session).contentType(APPLICATION_JSON)
                .content("{\"emp\":\"10001\",\"password\":\"pw-1234\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.emp").value("10001"))
            .andExpect(jsonPath("$.name").value("홍길동"))
            .andExpect(jsonPath("$.roleId").value("operator"))
            .andExpect(jsonPath("$.caps").isArray())
            .andExpect(jsonPath("$.caps", hasItem("res.read")));
        assertThat(session.getAttribute(AuthController.SESSION_USER)).isEqualTo("u1");
    }

    @Test
    void loginFailureIs401AndNoSession() throws Exception {
        MockHttpSession session = new MockHttpSession();
        mvc.perform(post("/api/auth/login").session(session).contentType(APPLICATION_JSON)
                .content("{\"emp\":\"10001\",\"password\":\"wrong\"}"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.error").exists());
        assertThat(session.getAttribute(AuthController.SESSION_USER)).isNull();
    }

    @Test
    void loginValidatesBody() throws Exception {
        mvc.perform(post("/api/auth/login").contentType(APPLICATION_JSON)
                .content("{\"emp\":\"\",\"password\":\"\"}"))
            .andExpect(status().isBadRequest());
    }

    @Test
    void logoutInvalidatesSession() throws Exception {
        MockHttpSession session = new MockHttpSession();
        mvc.perform(post("/api/auth/login").session(session).contentType(APPLICATION_JSON)
                .content("{\"emp\":\"10001\",\"password\":\"pw-1234\"}"))
            .andExpect(status().isOk());
        mvc.perform(post("/api/auth/logout").session(session))
            .andExpect(status().isNoContent())
            .andExpect(content().string(""));
        assertThat(session.isInvalid()).isTrue();
    }

    @Test
    void meInLocalModeReturnsSyntheticLocalAdmin() throws Exception {
        mvc.perform(get("/api/auth/me"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value("local"))
            .andExpect(jsonPath("$.roleId").value("admin"))
            .andExpect(jsonPath("$.caps", hasItem("admin.permissions")));   // 합성 admin도 caps 채움 — 모드 무관 동일 API
    }
}
