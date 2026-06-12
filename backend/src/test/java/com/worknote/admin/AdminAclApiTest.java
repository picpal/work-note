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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:sqlite:file:phase2mem?mode=memory&cache=shared",
    "worknote.mode=server",
    "worknote.admin-password=boot-pass-1"
})
@AutoConfigureMockMvc
class AdminAclApiTest {

    @Autowired MockMvc mvc;
    @Autowired UserMapper users;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM audit_log");
        jdbc.update("DELETE FROM acl");
        jdbc.update("DELETE FROM public_flag");
        jdbc.update("DELETE FROM space");
        jdbc.update("DELETE FROM team_member");
        jdbc.update("DELETE FROM team");
        jdbc.update("DELETE FROM node");
        jdbc.update("DELETE FROM user_credential WHERE user_id <> 'u-admin'");
        jdbc.update("DELETE FROM app_user WHERE id <> 'u-admin'");
        users.insert(new UserRow("u1", "10001", null, "홍길동", "operator", "active", null));
        String salt = PasswordHasher.newSalt();
        users.insertCredential(new CredentialRow("u1", salt, PasswordHasher.hash("pw-1234", salt)));
        jdbc.update("INSERT INTO team (id, name) VALUES ('t1','결제팀')");
        jdbc.update("INSERT INTO node (id, type, name, position) VALUES ('f1','folder','F1',1)");
        jdbc.update("INSERT INTO node (id, parent_id, type, name, position, content) VALUES ('n1','f1','note','N1',1,'body')");
    }

    private MockHttpSession login(String emp, String pw) throws Exception {
        MockHttpSession session = new MockHttpSession();
        mvc.perform(post("/api/auth/login").session(session).contentType(APPLICATION_JSON)
                .content("{\"emp\":\"" + emp + "\",\"password\":\"" + pw + "\"}"))
            .andExpect(status().isOk());
        return session;
    }

    @Test
    void put_replacesEntries_andTakesEffect() throws Exception {
        MockHttpSession admin = login("admin", "boot-pass-1");
        // 권한 없는 operator는 n1을 못 본다
        MockHttpSession op = login("10001", "pw-1234");
        mvc.perform(get("/api/tree").session(op)).andExpect(jsonPath("$.length()").value(0));
        // grant 부여
        mvc.perform(put("/api/admin/nodes/f1/acl").session(admin).contentType(APPLICATION_JSON)
                .content("{\"entries\":[{\"principalType\":\"user\",\"principalId\":\"u1\",\"grantType\":\"edit\"}]}"))
            .andExpect(status().isNoContent());
        mvc.perform(get("/api/tree").session(op)).andExpect(jsonPath("$.length()").value(1));
        // replace-all: 빈 entries로 회수
        mvc.perform(put("/api/admin/nodes/f1/acl").session(admin).contentType(APPLICATION_JSON)
                .content("{\"entries\":[]}"))
            .andExpect(status().isNoContent());
        mvc.perform(get("/api/tree").session(op)).andExpect(jsonPath("$.length()").value(0));
        assertThat(jdbc.queryForObject(
            "SELECT COUNT(*) FROM audit_log WHERE act = 'acl.set'", Integer.class)).isEqualTo(2);
    }

    @Test
    void getForNode_andGetAll() throws Exception {
        MockHttpSession admin = login("admin", "boot-pass-1");
        mvc.perform(put("/api/admin/nodes/f1/acl").session(admin).contentType(APPLICATION_JSON)
                .content("{\"entries\":[{\"principalType\":\"team\",\"principalId\":\"t1\",\"grantType\":\"read\"},"
                    + "{\"principalType\":\"all\",\"principalId\":\"@all\",\"grantType\":\"deny\"}]}"))
            .andExpect(status().isNoContent());
        mvc.perform(get("/api/admin/nodes/f1/acl").session(admin))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(2));
        mvc.perform(get("/api/admin/acl").session(admin))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    void put_unknownPrincipal_422() throws Exception {
        MockHttpSession admin = login("admin", "boot-pass-1");
        mvc.perform(put("/api/admin/nodes/f1/acl").session(admin).contentType(APPLICATION_JSON)
                .content("{\"entries\":[{\"principalType\":\"user\",\"principalId\":\"no-such\",\"grantType\":\"read\"}]}"))
            .andExpect(status().isUnprocessableEntity());
        mvc.perform(put("/api/admin/nodes/f1/acl").session(admin).contentType(APPLICATION_JSON)
                .content("{\"entries\":[{\"principalType\":\"all\",\"principalId\":\"everyone\",\"grantType\":\"read\"}]}"))
            .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void put_duplicatePrincipal_422_unknownNode_404() throws Exception {
        MockHttpSession admin = login("admin", "boot-pass-1");
        mvc.perform(put("/api/admin/nodes/f1/acl").session(admin).contentType(APPLICATION_JSON)
                .content("{\"entries\":[{\"principalType\":\"user\",\"principalId\":\"u1\",\"grantType\":\"read\"},"
                    + "{\"principalType\":\"user\",\"principalId\":\"u1\",\"grantType\":\"edit\"}]}"))
            .andExpect(status().isUnprocessableEntity());
        mvc.perform(put("/api/admin/nodes/no-such/acl").session(admin).contentType(APPLICATION_JSON)
                .content("{\"entries\":[]}"))
            .andExpect(status().isNotFound());
    }

    @Test
    void put_onSpaceFolder_withoutOwnerTeamGrant_auditsAbsence() throws Exception {
        MockHttpSession admin = login("admin", "boot-pass-1");
        jdbc.update("INSERT INTO space (node_id, team_id) VALUES ('f1','t1')");
        // 소유 팀 t1 엔트리 없이 replace — grant 재주입은 없고(replace-all 계약) 감사에 부재만 부기
        mvc.perform(put("/api/admin/nodes/f1/acl").session(admin).contentType(APPLICATION_JSON)
                .content("{\"entries\":[{\"principalType\":\"user\",\"principalId\":\"u1\",\"grantType\":\"read\"}]}"))
            .andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject(
            "SELECT COUNT(*) FROM acl WHERE node_id = 'f1' AND principal_type = 'team'", Integer.class)).isZero();
        assertThat(jdbc.queryForObject(
            "SELECT target FROM audit_log WHERE act = 'acl.set'", String.class))
            .isEqualTo("f1 (1건) (스페이스 소유 팀 t1 grant 부재)");
        // 소유 팀 엔트리를 포함하면 부기 없음
        mvc.perform(put("/api/admin/nodes/f1/acl").session(admin).contentType(APPLICATION_JSON)
                .content("{\"entries\":[{\"principalType\":\"team\",\"principalId\":\"t1\",\"grantType\":\"edit\"}]}"))
            .andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject(
            "SELECT target FROM audit_log WHERE act = 'acl.set' ORDER BY rowid DESC LIMIT 1", String.class))
            .isEqualTo("f1 (1건)");
    }

    @Test
    void put_softDeletedNode_404() throws Exception {
        MockHttpSession admin = login("admin", "boot-pass-1");
        jdbc.update("UPDATE node SET deleted_at = '2026-06-12T00:00:00' WHERE id = 'f1'");
        mvc.perform(put("/api/admin/nodes/f1/acl").session(admin).contentType(APPLICATION_JSON)
                .content("{\"entries\":[]}"))
            .andExpect(status().isNotFound());
    }

    @Test
    void put_invalidGrantType_400() throws Exception {
        MockHttpSession admin = login("admin", "boot-pass-1");
        mvc.perform(put("/api/admin/nodes/f1/acl").session(admin).contentType(APPLICATION_JSON)
                .content("{\"entries\":[{\"principalType\":\"user\",\"principalId\":\"u1\",\"grantType\":\"write\"}]}"))
            .andExpect(status().isBadRequest());
    }
}
