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

/**
 * MeResponse에 redmine 필드(enabled/tokenPresent) 포함 여부 검증.
 * GET /api/auth/me 응답에 redmine 객체가 있어야 한다.
 */
@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:sqlite:file:memdb-meredminefield?mode=memory&cache=shared",
    "worknote.mode=server", "worknote.admin-password=x",
    "worknote.totp.key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
})
@AutoConfigureMockMvc
class MeRedmineFieldTest {
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

    @Test void me_redmine_enabled_tokenNotPresent_beforeRegister() throws Exception {
        MockHttpSession s = login();
        mvc.perform(get("/api/auth/me").session(s))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.redmine.enabled").value(true))
            .andExpect(jsonPath("$.redmine.tokenPresent").value(false));
    }

    @Test void me_redmine_tokenPresent_afterRegister() throws Exception {
        when(client.fetchCurrentLogin(eq("http://redmine.intra"), eq("VALIDKEY"))).thenReturn("jdoe");
        MockHttpSession s = login();
        // 토큰 등록
        mvc.perform(put("/api/me/redmine/token").session(s)
                .contentType(APPLICATION_JSON)
                .content("{\"token\":\"VALIDKEY\"}"))
            .andExpect(status().isOk());
        // me 응답에 tokenPresent=true 확인
        mvc.perform(get("/api/auth/me").session(s))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.redmine.enabled").value(true))
            .andExpect(jsonPath("$.redmine.tokenPresent").value(true));
    }

    @Test void me_redmine_disabled_when_setting_off() throws Exception {
        settings.setRedmine(false, null);
        MockHttpSession s = login();
        mvc.perform(get("/api/auth/me").session(s))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.redmine.enabled").value(false))
            .andExpect(jsonPath("$.redmine.tokenPresent").value(false));
    }

    private MockHttpSession login() throws Exception {
        MockHttpSession s = new MockHttpSession();
        mvc.perform(post("/api/auth/login").session(s).contentType(APPLICATION_JSON)
            .content("{\"emp\":\"10001\",\"password\":\"pw-1234\"}")).andExpect(status().isOk());
        return s;
    }
}
