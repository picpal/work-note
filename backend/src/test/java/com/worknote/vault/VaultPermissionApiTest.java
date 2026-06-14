package com.worknote.vault;

import com.worknote.acl.AclMapper;
import com.worknote.acl.AclRow;
import com.worknote.auth.CredentialRow;
import com.worknote.auth.PasswordHasher;
import com.worknote.auth.UserMapper;
import com.worknote.auth.UserRow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;

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
class VaultPermissionApiTest {
    @Autowired MockMvc mvc;
    @Autowired UserMapper users;
    @Autowired NodeMapper nodes;
    @Autowired AclMapper acl;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM audit_log");
        jdbc.update("DELETE FROM acl");
        jdbc.update("DELETE FROM public_flag");
        jdbc.update("DELETE FROM team_member");
        jdbc.update("DELETE FROM team");
        jdbc.update("DELETE FROM tag");
        jdbc.update("DELETE FROM node");
        jdbc.update("DELETE FROM user_credential WHERE user_id <> 'u-admin'");
        jdbc.update("DELETE FROM app_user WHERE id <> 'u-admin'");
        createUser("u1", "10001", "operator");
        createUser("u2", "20002", "visitor");
        // 트리: f1 > n1
        nodes.insert(new NodeRow("f1", null, "folder", "F1", 1, null, null, null, null));
        nodes.insert(new NodeRow("n1", "f1", "note", "N1", 1, "body", "2026-06-11T09:00:00", null, null));
    }

    private void createUser(String id, String emp, String roleId) {
        users.insert(new UserRow(id, emp, null, "이름-" + emp, roleId, "active", null));
        String salt = PasswordHasher.newSalt();
        users.insertCredential(new CredentialRow(id, salt, PasswordHasher.hash("pw-1234", salt)));
    }

    private MockHttpSession login(String emp, String pw) throws Exception {
        MockHttpSession session = new MockHttpSession();
        mvc.perform(post("/api/auth/login").session(session).contentType(APPLICATION_JSON)
                .content("{\"emp\":\"" + emp + "\",\"password\":\"" + pw + "\"}"))
            .andExpect(status().isOk());
        return session;
    }

    @Test
    void treeIsFilteredByPermission() throws Exception {
        acl.insertAcl(new AclRow("user", "u1", "n1", "read"));
        MockHttpSession session = login("10001", "pw-1234");
        mvc.perform(get("/api/tree").session(session))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$", hasSize(1)))
            .andExpect(jsonPath("$[0].id").value("f1"))                  // 스텁 폴더
            .andExpect(jsonPath("$[0].children[0].id").value("n1"));
        MockHttpSession other = login("20002", "pw-1234");
        mvc.perform(get("/api/tree").session(other))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$", hasSize(0)));                       // grant 없음 — 빈 트리
    }

    @Test
    void createForbiddenWithoutParentEdit() throws Exception {
        MockHttpSession session = login("10001", "pw-1234");
        mvc.perform(post("/api/nodes").session(session).contentType(APPLICATION_JSON)
                .content("{\"id\":\"n2\",\"parentId\":\"f1\",\"type\":\"note\",\"name\":\"새 노트\"}"))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.error").exists());
        acl.insertAcl(new AclRow("user", "u1", "f1", "edit"));
        mvc.perform(post("/api/nodes").session(session).contentType(APPLICATION_JSON)
                .content("{\"id\":\"n2\",\"parentId\":\"f1\",\"type\":\"note\",\"name\":\"새 노트\"}"))
            .andExpect(status().isCreated());
        assertThat(jdbc.queryForObject(
            "SELECT COUNT(*) FROM audit_log WHERE act = 'node.create' AND who = '10001' AND target = 'n2'",
            Integer.class)).isEqualTo(1);
    }

    @Test
    void visitorEditCappedByRole() throws Exception {
        acl.insertAcl(new AclRow("user", "u2", "f1", "edit"));   // grant는 있지만 역할 상한이 read
        MockHttpSession session = login("20002", "pw-1234");
        mvc.perform(patch("/api/nodes/n1").session(session).contentType(APPLICATION_JSON)
                .content("{\"content\":\"변경\"}"))
            .andExpect(status().isForbidden());
        mvc.perform(get("/api/tree").session(session))
            .andExpect(jsonPath("$[0].children[0].id").value("n1"));    // read는 됨
    }

    @Test
    void deleteRequiresCapAndAuditLogged() throws Exception {
        acl.insertAcl(new AclRow("user", "u1", "f1", "edit"));
        MockHttpSession session = login("10001", "pw-1234");
        mvc.perform(delete("/api/nodes/n1").session(session)).andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject(
            "SELECT COUNT(*) FROM audit_log WHERE act = 'node.trash' AND who = '10001' AND target = 'n1'",
            Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT deleted_by FROM node WHERE id = 'n1'", String.class))
            .isEqualTo("10001");   // deleted_by = 사번
    }

    @Test
    void trashVisibilityAndRestorePolicy() throws Exception {
        acl.insertAcl(new AclRow("user", "u1", "f1", "edit"));
        MockHttpSession op = login("10001", "pw-1234");
        mvc.perform(delete("/api/nodes/n1").session(op)).andExpect(status().isNoContent());
        // 다른 사용자(방문자) — 휴지통 빈 목록 + 복구 403
        MockHttpSession visitor = login("20002", "pw-1234");
        mvc.perform(get("/api/trash").session(visitor))
            .andExpect(jsonPath("$", hasSize(0)));
        mvc.perform(post("/api/trash/n1/restore").session(visitor))
            .andExpect(status().isForbidden());
        // 삭제자 본인 — 목록 + 복구 가능
        mvc.perform(get("/api/trash").session(op)).andExpect(jsonPath("$", hasSize(1)));
        mvc.perform(post("/api/trash/n1/restore").session(op)).andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject(
            "SELECT COUNT(*) FROM audit_log WHERE act = 'node.restore' AND who = '10001'", Integer.class))
            .isEqualTo(1);
    }

    @Test
    void purgeIsAdminOnly() throws Exception {
        acl.insertAcl(new AclRow("user", "u1", "f1", "edit"));
        MockHttpSession op = login("10001", "pw-1234");
        mvc.perform(delete("/api/nodes/n1").session(op)).andExpect(status().isNoContent());
        mvc.perform(delete("/api/trash/n1").session(op)).andExpect(status().isForbidden());
        MockHttpSession admin = login("admin", "boot-pass-1");
        mvc.perform(delete("/api/trash/n1").session(admin)).andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject(
            "SELECT COUNT(*) FROM audit_log WHERE act = 'node.purge' AND who = 'admin'", Integer.class))
            .isEqualTo(1);
    }

    @Test
    void moveRequiresBothEndsAndAudited() throws Exception {
        nodes.insert(new NodeRow("f2", null, "folder", "F2", 2, null, null, null, null));
        acl.insertAcl(new AclRow("user", "u1", "f1", "edit"));
        MockHttpSession session = login("10001", "pw-1234");
        mvc.perform(post("/api/nodes/n1/move").session(session).contentType(APPLICATION_JSON)
                .content("{\"parentId\":\"f2\"}"))
            .andExpect(status().isForbidden());                          // 대상 edit 없음
        acl.insertAcl(new AclRow("user", "u1", "f2", "edit"));
        mvc.perform(post("/api/nodes/n1/move").session(session).contentType(APPLICATION_JSON)
                .content("{\"parentId\":\"f2\"}"))
            .andExpect(status().isNoContent());
        // 감사 target에 목적지 포함 — 이동에 따른 노출 변경 재구성용 (스펙 §7)
        assertThat(jdbc.queryForObject(
            "SELECT COUNT(*) FROM audit_log WHERE act = 'node.move' AND target = 'n1 -> f2'", Integer.class))
            .isEqualTo(1);
    }

    @Test
    void moveTrashedNodeIs404() throws Exception {
        nodes.insert(new NodeRow("f2", null, "folder", "F2", 2, null, null, null, null));
        MockHttpSession admin = login("admin", "boot-pass-1");
        mvc.perform(delete("/api/nodes/n1").session(admin)).andExpect(status().isNoContent());
        // 휴지통 노드는 이동 불가 — trash/update와 동일하게 requireActive가 404
        mvc.perform(post("/api/nodes/n1/move").session(admin).contentType(APPLICATION_JSON)
                .content("{\"parentId\":\"f2\"}"))
            .andExpect(status().isNotFound());
    }

    @Test
    void patchIsNotAudited() throws Exception {
        acl.insertAcl(new AclRow("user", "u1", "f1", "edit"));
        MockHttpSession session = login("10001", "pw-1234");
        mvc.perform(patch("/api/nodes/n1").session(session).contentType(APPLICATION_JSON)
                .content("{\"content\":\"수정\"}"))
            .andExpect(status().isOk());   // PATCH는 이제 pii JSON(200) — 감사는 여전히 미기록
        // login()이 login.success 1건을 이미 기록 — PATCH 미기록은 node.* 부재로 단언
        assertThat(jdbc.queryForObject(
            "SELECT COUNT(*) FROM audit_log WHERE act LIKE 'node.%'", Integer.class)).isZero();
    }

    @Test
    void loginAuditLogged() throws Exception {
        login("10001", "pw-1234");
        mvc.perform(post("/api/auth/login").contentType(APPLICATION_JSON)
                .content("{\"emp\":\"10001\",\"password\":\"wrong\"}"))
            .andExpect(status().isUnauthorized());
        assertThat(jdbc.queryForObject(
            "SELECT COUNT(*) FROM audit_log WHERE act = 'login.success' AND who = '10001'", Integer.class))
            .isEqualTo(1);
        assertThat(jdbc.queryForObject(
            "SELECT COUNT(*) FROM audit_log WHERE act = 'login.fail' AND who = '10001'", Integer.class))
            .isEqualTo(1);
    }

    @Test
    void adminSeesEverything() throws Exception {
        MockHttpSession admin = login("admin", "boot-pass-1");
        mvc.perform(get("/api/tree").session(admin))
            .andExpect(jsonPath("$", hasSize(1)))
            .andExpect(jsonPath("$[0].children", hasSize(1)));
    }
}
