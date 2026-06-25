package com.worknote.redmine;

import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.mockito.Mockito.*;

import com.worknote.auth.CredentialRow;
import com.worknote.auth.UserMapper;
import com.worknote.auth.UserRow;
import com.worknote.auth.PasswordHasher;
import com.worknote.setting.SettingService;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:sqlite:file:memdb-meredmineapi?mode=memory&cache=shared",
    "worknote.mode=server", "worknote.admin-password=x",
    "worknote.totp.key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
})
@AutoConfigureMockMvc
class MeRedmineApiTest {
    @Autowired MockMvc mvc;
    @Autowired UserMapper users;
    @Autowired JdbcTemplate jdbc;
    @Autowired SettingService settings;
    @MockBean RedmineClient client;

    @BeforeEach void clean() {
        jdbc.update("DELETE FROM user_redmine_token");
        jdbc.update("DELETE FROM user_credential");
        jdbc.update("DELETE FROM app_user");
        users.insert(new UserRow("u1", "10001", "a@corp.local", "홍", "operator", "active", null));
        String salt = PasswordHasher.newSalt();
        users.insertCredential(new CredentialRow("u1", salt, PasswordHasher.hash("pw-1234", salt)));
        settings.setRedmine(true, "http://redmine.intra");
    }

    @Test void get_status_tokenNotPresent() throws Exception {
        MockHttpSession s = login();
        mvc.perform(get("/api/me/redmine").session(s))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.enabled").value(true))
            .andExpect(jsonPath("$.tokenPresent").value(false));
    }

    @Test void put_token_success_returns_status() throws Exception {
        when(client.fetchCurrentLogin(eq("http://redmine.intra"), eq("VALIDKEY"))).thenReturn("jdoe");
        MockHttpSession s = login();
        mvc.perform(put("/api/me/redmine/token").session(s)
                .contentType(APPLICATION_JSON)
                .content("{\"token\":\"VALIDKEY\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.tokenPresent").value(true))
            .andExpect(jsonPath("$.redmineLogin").value("jdoe"));
    }

    @Test void put_token_invalid_returns_409() throws Exception {
        when(client.fetchCurrentLogin(anyString(), anyString()))
            .thenThrow(new RedmineException.Auth("redmine_token_invalid"));
        MockHttpSession s = login();
        mvc.perform(put("/api/me/redmine/token").session(s)
                .contentType(APPLICATION_JSON)
                .content("{\"token\":\"BADKEY\"}"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.error").value("redmine_token_invalid"));
    }

    @Test void delete_token_returns_204() throws Exception {
        when(client.fetchCurrentLogin(anyString(), anyString())).thenReturn("jdoe");
        MockHttpSession s = login();
        // 먼저 토큰 등록
        mvc.perform(put("/api/me/redmine/token").session(s)
                .contentType(APPLICATION_JSON)
                .content("{\"token\":\"VALIDKEY\"}"))
            .andExpect(status().isOk());
        // 삭제
        mvc.perform(delete("/api/me/redmine/token").session(s))
            .andExpect(status().isNoContent());
        // 삭제 후 status 확인
        mvc.perform(get("/api/me/redmine").session(s))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.tokenPresent").value(false));
    }

    private MockHttpSession login() throws Exception {
        MockHttpSession s = new MockHttpSession();
        mvc.perform(post("/api/auth/login").session(s).contentType(APPLICATION_JSON)
            .content("{\"emp\":\"10001\",\"password\":\"pw-1234\"}")).andExpect(status().isOk());
        return s;
    }
}
