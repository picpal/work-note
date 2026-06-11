package com.worknote.admin;

import com.worknote.auth.CredentialRow;
import com.worknote.auth.PasswordHasher;
import com.worknote.auth.UserMapper;
import com.worknote.auth.UserRow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:sqlite:file:phase2mem?mode=memory&cache=shared",
    "worknote.mode=server",
    "worknote.admin-password=boot-pass-1"
})
@AutoConfigureMockMvc
class AdminTeamApiTest {

    @Autowired MockMvc mvc;
    @Autowired UserMapper users;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM audit_log");
        jdbc.update("DELETE FROM acl");
        jdbc.update("DELETE FROM space");
        jdbc.update("DELETE FROM team_member");
        jdbc.update("DELETE FROM team");
        jdbc.update("DELETE FROM node");
        jdbc.update("DELETE FROM user_credential WHERE user_id <> 'u-admin'");
        jdbc.update("DELETE FROM app_user WHERE id <> 'u-admin'");
        users.insert(new UserRow("u1", "10001", null, "홍길동", "operator", "active", null));
        String salt = PasswordHasher.newSalt();
        users.insertCredential(new CredentialRow("u1", salt, PasswordHasher.hash("pw-1234", salt)));
    }

    private MockHttpSession admin() throws Exception {
        return login("admin", "boot-pass-1");
    }

    private MockHttpSession login(String emp, String pw) throws Exception {
        MockHttpSession session = new MockHttpSession();
        mvc.perform(post("/api/auth/login").session(session).contentType(APPLICATION_JSON)
                .content("{\"emp\":\"" + emp + "\",\"password\":\"" + pw + "\"}"))
            .andExpect(status().isOk());
        return session;
    }

    private String createTeam(MockHttpSession s, String name) throws Exception {
        MvcResult res = mvc.perform(post("/api/admin/teams").session(s).contentType(APPLICATION_JSON)
                .content("{\"name\":\"" + name + "\"}"))
            .andExpect(status().isCreated()).andReturn();
        return com.jayway.jsonpath.JsonPath.read(res.getResponse().getContentAsString(), "$.id");
    }

    @Test
    void create_addMember_list() throws Exception {
        MockHttpSession s = admin();
        String teamId = createTeam(s, "결제팀");
        mvc.perform(post("/api/admin/teams/" + teamId + "/members").session(s).contentType(APPLICATION_JSON)
                .content("{\"userId\":\"u1\"}"))
            .andExpect(status().isNoContent());
        mvc.perform(get("/api/admin/teams").session(s))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].members[0].emp").value("10001"));
    }

    @Test
    void addMember_duplicate_409_unknownUser_422() throws Exception {
        MockHttpSession s = admin();
        String teamId = createTeam(s, "결제팀");
        mvc.perform(post("/api/admin/teams/" + teamId + "/members").session(s).contentType(APPLICATION_JSON)
                .content("{\"userId\":\"u1\"}")).andExpect(status().isNoContent());
        mvc.perform(post("/api/admin/teams/" + teamId + "/members").session(s).contentType(APPLICATION_JSON)
                .content("{\"userId\":\"u1\"}")).andExpect(status().isConflict());
        mvc.perform(post("/api/admin/teams/" + teamId + "/members").session(s).contentType(APPLICATION_JSON)
                .content("{\"userId\":\"no-such\"}")).andExpect(status().isUnprocessableEntity());
    }

    @Test
    void removeMember_thenTeamAclNoLongerApplies() throws Exception {
        MockHttpSession s = admin();
        String teamId = createTeam(s, "결제팀");
        jdbc.update("INSERT INTO node (id, type, name, position) VALUES ('f1','folder','F1',1)");
        jdbc.update("INSERT INTO acl (principal_type, principal_id, node_id, grant_type) VALUES ('team', ?, 'f1', 'edit')", teamId);
        mvc.perform(post("/api/admin/teams/" + teamId + "/members").session(s).contentType(APPLICATION_JSON)
                .content("{\"userId\":\"u1\"}")).andExpect(status().isNoContent());
        // 멤버십 추가 직후 — 팀 ACL 경유로 f1이 보임
        MockHttpSession u1 = login("10001", "pw-1234");
        mvc.perform(get("/api/tree").session(u1))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$", hasSize(1)))
            .andExpect(jsonPath("$[0].id").value("f1"));
        mvc.perform(delete("/api/admin/teams/" + teamId + "/members/u1").session(s))
            .andExpect(status().isNoContent());
        // 멤버십 제거 즉시 권한 소실 — 같은 세션 재조회에서 빈 트리 (회귀 방어)
        mvc.perform(get("/api/tree").session(u1))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$", hasSize(0)));
        // 감사 target은 emp로 기록 (user 계열 감사와 정합)
        assertThat(jdbc.queryForObject(
            "SELECT COUNT(*) FROM audit_log WHERE act = 'team.member.remove' AND target = ?",
            Integer.class, teamId + " - 10001")).isEqualTo(1);
        mvc.perform(delete("/api/admin/teams/" + teamId + "/members/u1").session(s))
            .andExpect(status().isNotFound());
    }

    @Test
    void deleteTeam_cleansMembershipAndAcl() throws Exception {
        MockHttpSession s = admin();
        String teamId = createTeam(s, "결제팀");
        jdbc.update("INSERT INTO node (id, type, name, position) VALUES ('f1','folder','F1',1)");
        jdbc.update("INSERT INTO acl (principal_type, principal_id, node_id, grant_type) VALUES ('team', ?, 'f1', 'edit')", teamId);
        mvc.perform(post("/api/admin/teams/" + teamId + "/members").session(s).contentType(APPLICATION_JSON)
                .content("{\"userId\":\"u1\"}")).andExpect(status().isNoContent());
        mvc.perform(delete("/api/admin/teams/" + teamId).session(s)).andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM team_member WHERE team_id = ?", Integer.class, teamId)).isZero();
        assertThat(jdbc.queryForObject(
            "SELECT COUNT(*) FROM acl WHERE principal_type = 'team' AND principal_id = ?", Integer.class, teamId)).isZero();
    }

    @Test
    void deleteTeam_owningSpace_409() throws Exception {
        MockHttpSession s = admin();
        String teamId = createTeam(s, "결제팀");
        jdbc.update("INSERT INTO node (id, type, name, position) VALUES ('f1','folder','F1',1)");
        jdbc.update("INSERT INTO space (node_id, team_id) VALUES ('f1', ?)", teamId);
        mvc.perform(delete("/api/admin/teams/" + teamId).session(s)).andExpect(status().isConflict());
    }

    @Test
    void rename_204_unknown_404() throws Exception {
        MockHttpSession s = admin();
        String teamId = createTeam(s, "결제팀");
        mvc.perform(patch("/api/admin/teams/" + teamId).session(s).contentType(APPLICATION_JSON)
                .content("{\"name\":\"정산팀\"}")).andExpect(status().isNoContent());
        mvc.perform(patch("/api/admin/teams/no-such").session(s).contentType(APPLICATION_JSON)
                .content("{\"name\":\"x\"}")).andExpect(status().isNotFound());
    }
}
