package com.worknote.auth;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * T5 — local 모드(무인증 1단계)는 Origin 검증 대상이 아니다.
 * AuthFilterConfig가 server 모드에서만 필터를 등록하므로 구조적으로 미적용이지만, 회귀로 고정한다.
 */
@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:sqlite:file:memdb-origin-local?mode=memory&cache=shared"
})
@AutoConfigureMockMvc
class OriginLocalModeTest {
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
    void crossOriginPostIsNotBlockedInLocalMode() throws Exception {
        mvc.perform(post("/api/auth/login").header("Origin", "https://evil.domain.co.kr")
                .contentType(APPLICATION_JSON)
                .content("{\"emp\":\"10001\",\"password\":\"pw-1234\"}"))
            .andExpect(status().isOk());
    }
}
