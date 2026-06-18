package com.worknote.auth;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.contains;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 사용자 디렉토리(server 모드): active emp+name만, 민감필드 미노출, 미인증 401. */
@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:sqlite:file:dirmem?mode=memory&cache=shared",
    "worknote.mode=server",
    "worknote.admin-password=boot-pass-1"
})
@AutoConfigureMockMvc
class UserDirectoryApiTest {
    @Autowired MockMvc mvc;
    @Autowired UserMapper users;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void seed() {
        jdbc.update("DELETE FROM user_credential WHERE user_id <> 'u-admin'");
        jdbc.update("DELETE FROM app_user WHERE id <> 'u-admin'");
        users.insert(new UserRow("u1", "10001", "a@x.com", "홍길동", "operator", "active", null));
        users.insert(new UserRow("u2", "20002", null, "김철수", "operator", "disabled", null));
        users.insert(new UserRow("u3", "30003", null, "이영희", "visitor", "pending", null));
        String salt = PasswordHasher.newSalt();
        users.insertCredential(new CredentialRow("u1", salt, PasswordHasher.hash("pw-1234", salt)));
    }

    private MockHttpSession login(String emp) throws Exception {
        MockHttpSession s = new MockHttpSession();
        mvc.perform(post("/api/auth/login").session(s).contentType(APPLICATION_JSON)
                .content("{\"emp\":\"" + emp + "\",\"password\":\"pw-1234\"}"))
            .andExpect(status().isOk());
        return s;
    }

    @Test
    void directory_returnsOnlyActiveEmpAndName() throws Exception {
        MockHttpSession s = login("10001");
        mvc.perform(get("/api/users/directory").session(s))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[?(@.emp=='10001')].name").value(contains("홍길동")))
            .andExpect(jsonPath("$[?(@.emp=='20002')]").isEmpty())
            .andExpect(jsonPath("$[?(@.emp=='30003')]").isEmpty())
            .andExpect(jsonPath("$[0].email").doesNotExist())
            .andExpect(jsonPath("$[0].roleId").doesNotExist())
            .andExpect(jsonPath("$[0].status").doesNotExist());
    }

    @Test
    void directory_unauthenticated_is401() throws Exception {
        mvc.perform(get("/api/users/directory")).andExpect(status().isUnauthorized());
    }
}
