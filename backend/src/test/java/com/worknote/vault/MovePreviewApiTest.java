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
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 이동 미리보기 엔드포인트(GET /nodes/{id}/move-preview) + 이동 노출 감사 보강 — 스펙 §7.
 * 셋업은 VaultPermissionApiTest 컨벤션(f1>n1, u1=operator, u2=visitor, admin)을 따른다.
 */
@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:sqlite:file:phase2mem?mode=memory&cache=shared",
    "worknote.mode=server",
    "worknote.admin-password=boot-pass-1"
})
@AutoConfigureMockMvc
class MovePreviewApiTest {
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
        jdbc.update("DELETE FROM space");
        jdbc.update("DELETE FROM team_member");
        jdbc.update("DELETE FROM team");
        jdbc.update("DELETE FROM tag");
        jdbc.update("DELETE FROM node");
        jdbc.update("DELETE FROM user_credential WHERE user_id <> 'u-admin'");
        jdbc.update("DELETE FROM app_user WHERE id <> 'u-admin'");
        createUser("u1", "10001", "operator");
        createUser("u2", "20002", "visitor");
        // 트리: f1 > n1, 그리고 형제 폴더 f2(빈 폴더), pub(public 폴더)
        nodes.insert(new NodeRow("f1", null, "folder", "F1", 1, null, null, null, null));
        nodes.insert(new NodeRow("n1", "f1", "note", "N1", 1, "body", "2026-06-11T09:00:00", null, null));
        nodes.insert(new NodeRow("f2", null, "folder", "F2", 2, null, null, null, null));
        nodes.insert(new NodeRow("pub", null, "folder", "PUB", 3, null, null, null, null));
        acl.upsertPublicFlag("pub", "public");
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

    // 1. edit 가능 노트 → edit 가능 폴더 preview → 200 + JSON 필드 존재
    @Test
    void previewReturnsExposureFields() throws Exception {
        acl.insertAcl(new AclRow("user", "u1", "f1", "edit"));
        acl.insertAcl(new AclRow("user", "u1", "f2", "edit"));
        MockHttpSession op = login("10001", "pw-1234");
        mvc.perform(get("/api/nodes/n1/move-preview").session(op).param("parentId", "f2"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.publicBefore").exists())
            .andExpect(jsonPath("$.publicAfter").exists())
            .andExpect(jsonPath("$.crossSpace").exists())
            .andExpect(jsonPath("$.fromSpace").doesNotExist())   // space 미설정 = null
            .andExpect(jsonPath("$.added").isArray())
            .andExpect(jsonPath("$.removed").isArray());
    }

    // 2. 비공개 노트 → public 폴더 preview → publicAfter=true, publicBefore=false
    @Test
    void previewPrivateNoteIntoPublicFolder() throws Exception {
        acl.insertAcl(new AclRow("user", "u1", "f1", "edit"));
        acl.insertAcl(new AclRow("user", "u1", "pub", "edit"));
        MockHttpSession op = login("10001", "pw-1234");
        mvc.perform(get("/api/nodes/n1/move-preview").session(op).param("parentId", "pub"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.publicBefore").value(false))
            .andExpect(jsonPath("$.publicAfter").value(true));
    }

    // 3. edit 불가(노트에 deny) 사용자 preview → 403
    @Test
    void previewForbiddenWithoutEdit() throws Exception {
        acl.insertAcl(new AclRow("user", "u1", "f1", "deny"));   // 원본 노트 deny
        acl.insertAcl(new AclRow("user", "u1", "f2", "edit"));
        MockHttpSession op = login("10001", "pw-1234");
        mvc.perform(get("/api/nodes/n1/move-preview").session(op).param("parentId", "f2"))
            .andExpect(status().isForbidden());
    }

    // 4. 폴더를 자기 자손 폴더로 preview → 422 (사이클)
    @Test
    void previewIntoOwnDescendantIs422() throws Exception {
        // f1 > sub(폴더) — f1 을 sub 아래로 이동 시도 = 사이클
        nodes.insert(new NodeRow("sub", "f1", "folder", "SUB", 2, null, null, null, null));
        MockHttpSession admin = login("admin", "boot-pass-1");
        mvc.perform(get("/api/nodes/f1/move-preview").session(admin).param("parentId", "sub"))
            .andExpect(status().isUnprocessableEntity());
    }

    // 5. 관리자 세션으로 미존재 id preview → 404 (권한 통과 후 검증이 404)
    @Test
    void previewMissingNodeIs404ForAdmin() throws Exception {
        MockHttpSession admin = login("admin", "boot-pass-1");
        mvc.perform(get("/api/nodes/no-such/move-preview").session(admin).param("parentId", "f2"))
            .andExpect(status().isNotFound());
    }

    // 6. 실제 move(비공개 노트→public 폴더) 후 audit target에 [공개노출 시작] 접미사
    @Test
    void moveIntoPublicFolderAuditsExposureSuffix() throws Exception {
        MockHttpSession admin = login("admin", "boot-pass-1");
        mvc.perform(post("/api/nodes/n1/move").session(admin).contentType(APPLICATION_JSON)
                .content("{\"parentId\":\"pub\"}"))
            .andExpect(status().isNoContent());
        String target = jdbc.queryForObject(
            "SELECT target FROM audit_log WHERE act = 'node.move' ORDER BY id DESC LIMIT 1", String.class);
        assertThat(target).isEqualTo("n1 -> pub [공개노출 시작]");
    }

    // 7. 변경 없는 이동(같은 상속 형제 폴더) 후 audit target은 접미사 없음
    @Test
    void moveWithNoExposureChangeAuditsBareTarget() throws Exception {
        // root 폴더에 팀 grant + public → 자식 형제 폴더 s1, s2 동일 상속 → delta 상쇄(접미사 없음)
        nodes.insert(new NodeRow("root", null, "folder", "ROOT", 4, null, null, null, null));
        nodes.insert(new NodeRow("s1", "root", "folder", "S1", 1, null, null, null, null));
        nodes.insert(new NodeRow("s2", "root", "folder", "S2", 2, null, null, null, null));
        nodes.insert(new NodeRow("nn", "s1", "note", "NN", 1, "x", "2026-06-11T09:00:00", null, null));
        // 상속이 root에서 내려오므로 s1·s2 양쪽에서 동일 — 이동해도 노출 불변
        acl.insertAcl(new AclRow("team", "t-ops", "root", "read"));
        acl.upsertPublicFlag("root", "public");
        MockHttpSession admin = login("admin", "boot-pass-1");
        mvc.perform(post("/api/nodes/nn/move").session(admin).contentType(APPLICATION_JSON)
                .content("{\"parentId\":\"s2\"}"))
            .andExpect(status().isNoContent());
        String target = jdbc.queryForObject(
            "SELECT target FROM audit_log WHERE act = 'node.move' ORDER BY id DESC LIMIT 1", String.class);
        assertThat(target).isEqualTo("nn -> s2");
    }
}
