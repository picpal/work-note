package com.worknote.share;

import com.jayway.jsonpath.JsonPath;
import com.worknote.acl.AclMapper;
import com.worknote.acl.AclRow;
import com.worknote.auth.CredentialRow;
import com.worknote.auth.PasswordHasher;
import com.worknote.auth.UserMapper;
import com.worknote.auth.UserRow;
import com.worknote.vault.NodeMapper;
import com.worknote.vault.NodeRow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 공유 링크 API 통합 — 가드(res.share ∧ read)·deny 우회 열람·감사·purge 합류 (스펙 §6·§7). */
@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:sqlite:file:shareapimem?mode=memory&cache=shared",
    "worknote.mode=server",
    "worknote.admin-password=boot-pass-1"
})
@AutoConfigureMockMvc
class ShareApiTest {
    @Autowired MockMvc mvc;
    @Autowired UserMapper users;
    @Autowired NodeMapper nodes;
    @Autowired AclMapper acl;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM audit_log");
        jdbc.update("DELETE FROM share_link");
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

    /** body=null이면 본문 생략(기본값 생성). @return 응답 JSON 문자열. */
    private String createShare(MockHttpSession session, String nodeId, String body) throws Exception {
        var req = post("/api/nodes/" + nodeId + "/share").session(session);
        if (body != null) {
            req = req.contentType(APPLICATION_JSON).content(body);
        }
        return mvc.perform(req).andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
    }

    // 1. 생성 성공 — 201 + token 43자 + 감사(target에 token 미포함, linkId·nodeId 포함)
    @Test
    void createReturnsTokenAndAuditsWithoutToken() throws Exception {
        acl.insertAcl(new AclRow("user", "u1", "n1", "read"));
        MockHttpSession op = login("10001", "pw-1234");
        String res = createShare(op, "n1", null);   // body 생략 — 기본 7일
        String linkId = JsonPath.read(res, "$.id");
        String token = JsonPath.read(res, "$.token");
        assertThat(token).hasSize(43);
        assertThat((String) JsonPath.read(res, "$.expiresAt")).isNotBlank();
        String target = jdbc.queryForObject(
            "SELECT target FROM audit_log WHERE act = 'share.create' AND who = '10001'", String.class);
        assertThat(target).isEqualTo(linkId + " -> n1");   // 결정 S6: token 원문 비기록
        assertThat(target).doesNotContain(token);
    }

    // 2. 역할 상한 — visitor는 res.share 없음
    @Test
    void createForbiddenWithoutShareCap() throws Exception {
        acl.insertAcl(new AclRow("user", "u2", "n1", "read"));
        MockHttpSession visitor = login("20002", "pw-1234");
        mvc.perform(post("/api/nodes/n1/share").session(visitor))
            .andExpect(status().isForbidden());
    }

    // 3. ACL 범위 — deny 걸린 노트는 res.share가 있어도 생성 불가
    @Test
    void createForbiddenOnDeniedNode() throws Exception {
        acl.insertAcl(new AclRow("user", "u1", "n1", "deny"));
        MockHttpSession op = login("10001", "pw-1234");
        mvc.perform(post("/api/nodes/n1/share").session(op))
            .andExpect(status().isForbidden());
    }

    // 4. ★핵심★ deny 걸린 사용자도 링크로는 열람 — deny를 넘는 유일 예외 (스펙 §6)
    @Test
    void deniedUserCanViewThroughShareLink() throws Exception {
        acl.insertAcl(new AclRow("user", "u1", "n1", "deny"));
        MockHttpSession admin = login("admin", "boot-pass-1");
        String token = JsonPath.read(createShare(admin, "n1", null), "$.token");
        MockHttpSession denied = login("10001", "pw-1234");
        mvc.perform(get("/api/share/" + token).session(denied))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.name").value("N1"))
            .andExpect(jsonPath("$.content").value("body"))
            .andExpect(jsonPath("$.updatedAt").value("2026-06-11"));
        String linkId = jdbc.queryForObject("SELECT id FROM share_link WHERE token = ?", String.class, token);
        assertThat(jdbc.queryForObject(
            "SELECT COUNT(*) FROM audit_log WHERE act = 'share.view' AND who = '10001' AND target = ?",
            Integer.class, linkId + " -> n1")).isEqualTo(1);
    }

    // 5. 폴더는 공유 불가 — 가드 통과 후 서비스가 422
    @Test
    void createOnFolderIs422() throws Exception {
        acl.insertAcl(new AclRow("user", "u1", "f1", "read"));
        MockHttpSession op = login("10001", "pw-1234");
        mvc.perform(post("/api/nodes/f1/share").session(op))
            .andExpect(status().isUnprocessableEntity());
    }

    // 6. 무세션 열람 — AuthFilter가 401 (ALLOWLIST 미포함)
    @Test
    void viewWithoutSessionIs401() throws Exception {
        MockHttpSession admin = login("admin", "boot-pass-1");
        String token = JsonPath.read(createShare(admin, "n1", null), "$.token");
        mvc.perform(get("/api/share/" + token))
            .andExpect(status().isUnauthorized());
    }

    // 7. pin — 불일치 404(사유 비노출), 일치 200
    @Test
    void pinMismatch404AndMatch200() throws Exception {
        MockHttpSession admin = login("admin", "boot-pass-1");
        String token = JsonPath.read(
            createShare(admin, "n1", "{\"pinEmps\":[\"10001\"]}"), "$.token");
        MockHttpSession other = login("20002", "pw-1234");
        mvc.perform(get("/api/share/" + token).session(other))
            .andExpect(status().isNotFound());
        MockHttpSession pinned = login("10001", "pw-1234");
        mvc.perform(get("/api/share/" + token).session(pinned))
            .andExpect(status().isOk());
    }

    // 8. 취소 — 타인 403 / 본인 204 + 감사 / 재취소 409
    @Test
    void revokeOwnershipAndConflict() throws Exception {
        acl.insertAcl(new AclRow("user", "u1", "n1", "read"));
        MockHttpSession op = login("10001", "pw-1234");
        String linkId = JsonPath.read(createShare(op, "n1", null), "$.id");
        MockHttpSession other = login("20002", "pw-1234");
        mvc.perform(delete("/api/shares/" + linkId).session(other))
            .andExpect(status().isForbidden());
        mvc.perform(delete("/api/shares/" + linkId).session(op))
            .andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject(
            "SELECT COUNT(*) FROM audit_log WHERE act = 'share.revoke' AND who = '10001' AND target = ?",
            Integer.class, linkId + " -> n1")).isEqualTo(1);
        mvc.perform(delete("/api/shares/" + linkId).session(op))
            .andExpect(status().isConflict());
    }

    // 9. 관리자 전체 목록 — nodeName·suspended 포함, 비관리자 403, 조회는 감사 없음
    @Test
    void adminListsActiveSharesNonAdminForbidden() throws Exception {
        MockHttpSession admin = login("admin", "boot-pass-1");
        createShare(admin, "n1", null);
        mvc.perform(get("/api/admin/shares").session(admin))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$", hasSize(1)))
            .andExpect(jsonPath("$[0].nodeId").value("n1"))
            .andExpect(jsonPath("$[0].nodeName").value("N1"))
            .andExpect(jsonPath("$[0].suspended").value(false))
            .andExpect(jsonPath("$[0].createdBy").value("admin"));
        MockHttpSession op = login("10001", "pw-1234");
        mvc.perform(get("/api/admin/shares").session(op))
            .andExpect(status().isForbidden());
        assertThat(jdbc.queryForObject(
            "SELECT COUNT(*) FROM audit_log WHERE act LIKE 'share.%' AND act <> 'share.create'",
            Integer.class)).isZero();
    }

    // 10. 휴지통 = suspend — 열람 404, 복구하면 다시 200 (결정 S3)
    @Test
    void trashSuspendsAndRestoreRevives() throws Exception {
        MockHttpSession admin = login("admin", "boot-pass-1");
        String token = JsonPath.read(createShare(admin, "n1", null), "$.token");
        mvc.perform(delete("/api/nodes/n1").session(admin)).andExpect(status().isNoContent());
        mvc.perform(get("/api/share/" + token).session(admin))
            .andExpect(status().isNotFound());
        mvc.perform(post("/api/trash/n1/restore").session(admin)).andExpect(status().isNoContent());
        mvc.perform(get("/api/share/" + token).session(admin))
            .andExpect(status().isOk());
    }

    // 11. purge 합류 — share_link 고아 행 0 (id 재생성 fail-open 방지, 결정 S4)
    @Test
    void purgeDeletesShareLinks() throws Exception {
        MockHttpSession admin = login("admin", "boot-pass-1");
        createShare(admin, "n1", null);
        // 폴더째 trash → 폴더 purge — 서브트리 종속행까지 삭제되는지
        mvc.perform(delete("/api/nodes/f1").session(admin)).andExpect(status().isNoContent());
        mvc.perform(delete("/api/trash/f1").session(admin)).andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM share_link", Integer.class)).isZero();
    }

    // 12. 노드별 목록 — 생성자는 본인 것만, 관리자는 전체 + pinEmps 배열 직렬화
    @Test
    void listForNodeScopedByCreator() throws Exception {
        acl.insertAcl(new AclRow("user", "u1", "n1", "read"));
        MockHttpSession op = login("10001", "pw-1234");
        String mine = JsonPath.read(createShare(op, "n1", null), "$.id");
        MockHttpSession admin = login("admin", "boot-pass-1");
        createShare(admin, "n1", "{\"days\":3,\"maxViews\":5,\"pinEmps\":[\"10001\",\"20002\"]}");
        mvc.perform(get("/api/nodes/n1/shares").session(op))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$", hasSize(1)))
            .andExpect(jsonPath("$[0].id").value(mine))
            .andExpect(jsonPath("$[0].createdBy").value("10001"))
            .andExpect(jsonPath("$[0].pinEmps").value(nullValue()));   // pin 미설정 = null(전 직원)
        mvc.perform(get("/api/nodes/n1/shares").session(admin))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$", hasSize(2)))
            .andExpect(jsonPath("$[?(@.createdBy=='admin')].maxViews").value(5))
            .andExpect(jsonPath("$[?(@.createdBy=='admin')].pinEmps[0]").value("10001"))
            .andExpect(jsonPath("$[?(@.createdBy=='admin')].pinEmps[1]").value("20002"));
    }
}
