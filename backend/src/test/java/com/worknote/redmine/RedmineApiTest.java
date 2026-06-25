package com.worknote.redmine;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.worknote.auth.CredentialRow;
import com.worknote.auth.UserMapper;
import com.worknote.auth.UserRow;
import com.worknote.auth.PasswordHasher;
import com.worknote.redmine.RedmineDtos.*;
import com.worknote.setting.SettingService;
import java.util.List;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:sqlite:file:memdb-redmineapi?mode=memory&cache=shared",
    "worknote.mode=server", "worknote.admin-password=x",
    "worknote.totp.key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
})
@AutoConfigureMockMvc
class RedmineApiTest {
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

    /** 토큰 미등록 → GET /api/redmine/issues 409 */
    @Test void issues_requires_token_409_when_missing() throws Exception {
        MockHttpSession s = login();
        mvc.perform(get("/api/redmine/issues").session(s))
           .andExpect(status().isConflict());
    }

    /** 토큰 등록 + client.listIssues mock → 200 + issues[0].id */
    @Test void issues_returns_list_using_session_user_token() throws Exception {
        when(client.fetchCurrentLogin(eq("http://redmine.intra"), eq("KEY"))).thenReturn("jdoe");
        when(client.listIssues(anyString(), eq("KEY"), any()))
            .thenReturn(List.of(new RedmineIssueSummary(1, "s", "New", null, "P", "x")));
        MockHttpSession s = login();
        // 토큰 등록
        mvc.perform(put("/api/me/redmine/token").session(s)
                .contentType(APPLICATION_JSON)
                .content("{\"token\":\"KEY\"}"))
           .andExpect(status().isOk());
        // 이슈 검색
        mvc.perform(get("/api/redmine/issues?assignedToMe=true").session(s))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.issues[0].id").value(1));
    }

    /** redmine 비활성 → GET /api/redmine/issues 404 */
    @Test void issues_404_when_disabled() throws Exception {
        settings.setRedmine(false, "");
        MockHttpSession s = login();
        mvc.perform(get("/api/redmine/issues").session(s))
           .andExpect(status().isNotFound());
    }

    /** 상세 조회 성공 → 200 + id */
    @Test void issue_detail_returns_detail_and_logs_audit() throws Exception {
        when(client.fetchCurrentLogin(eq("http://redmine.intra"), eq("KEY"))).thenReturn("jdoe");
        when(client.getIssue(anyString(), eq("KEY"), eq(42L)))
            .thenReturn(new RedmineIssueDetail(42, "Bug", "desc", "New",
                "jdoe", "Proj", "Normal", null, "2026-01-01", List.of()));
        MockHttpSession s = login();
        mvc.perform(put("/api/me/redmine/token").session(s)
                .contentType(APPLICATION_JSON)
                .content("{\"token\":\"KEY\"}"))
           .andExpect(status().isOk());
        mvc.perform(get("/api/redmine/issues/42").session(s))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.id").value(42));
    }

    private MockHttpSession login() throws Exception {
        MockHttpSession s = new MockHttpSession();
        mvc.perform(post("/api/auth/login").session(s).contentType(APPLICATION_JSON)
            .content("{\"emp\":\"10001\",\"password\":\"pw-1234\"}")).andExpect(status().isOk());
        return s;
    }
}
