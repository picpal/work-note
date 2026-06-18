package com.worknote.vault;

import com.jayway.jsonpath.JsonPath;
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
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 다운로드 감사(server 모드): 내보내기 핑(note.export) + 첨부 다운로드(attachment.download).
    local 모드는 audit.log(null) no-op이라 server 모드로만 검증. */
@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:sqlite:file:dlauditmem?mode=memory&cache=shared",
    "worknote.mode=server",
    "worknote.admin-password=boot-pass-1",
    "worknote.upload.dir=build/test-dlaudit"
})
@AutoConfigureMockMvc
class DownloadAuditApiTest {
    @Autowired MockMvc mvc;
    @Autowired UserMapper users;
    @Autowired NodeMapper nodes;
    @Autowired AclMapper acl;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void seed() {
        jdbc.update("DELETE FROM audit_log");
        jdbc.update("DELETE FROM attachment");
        jdbc.update("DELETE FROM acl");
        jdbc.update("DELETE FROM user_credential WHERE user_id <> 'u-admin'");
        jdbc.update("DELETE FROM app_user WHERE id <> 'u-admin'");
        jdbc.update("DELETE FROM node");
        createUser("u1", "10001", "operator");
        createUser("u2", "20002", "visitor");
        nodes.insert(new NodeRow("n1", null, "note", "N1", 1, "body", "2026-06-11T09:00:00", null, null));
        acl.insertAcl(new AclRow("user", "u1", "n1", "edit")); // edit ⊇ read — 업로드(edit)+다운로드/내보내기(read) 모두
    }

    private void createUser(String id, String emp, String roleId) {
        users.insert(new UserRow(id, emp, null, "이름-" + emp, roleId, "active", null));
        String salt = PasswordHasher.newSalt();
        users.insertCredential(new CredentialRow(id, salt, PasswordHasher.hash("pw-1234", salt)));
    }

    private MockHttpSession login(String emp) throws Exception {
        MockHttpSession session = new MockHttpSession();
        mvc.perform(post("/api/auth/login").session(session).contentType(APPLICATION_JSON)
                .content("{\"emp\":\"" + emp + "\",\"password\":\"pw-1234\"}"))
            .andExpect(status().isOk());
        return session;
    }

    private int auditCount(String act) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE act = ?", Integer.class, act);
    }

    // ---- note.export ----

    @Test
    void exportLog_recordsNoteExportAudit() throws Exception {
        MockHttpSession s = login("10001");
        mvc.perform(post("/api/nodes/n1/export-log").session(s).contentType(APPLICATION_JSON)
                .content("{\"format\":\"md\"}"))
            .andExpect(status().isNoContent());
        assertThat(auditCount("note.export")).isEqualTo(1);
        String who = jdbc.queryForObject("SELECT who FROM audit_log WHERE act='note.export'", String.class);
        String target = jdbc.queryForObject("SELECT target FROM audit_log WHERE act='note.export'", String.class);
        assertThat(who).isEqualTo("10001");
        assertThat(target).isEqualTo("n1 (md)");
    }

    @Test
    void exportLog_normalizesUnknownFormat() throws Exception {
        MockHttpSession s = login("10001");
        mvc.perform(post("/api/nodes/n1/export-log").session(s).contentType(APPLICATION_JSON)
                .content("{\"format\":\"xlsx\"}"))
            .andExpect(status().isNoContent());
        String target = jdbc.queryForObject("SELECT target FROM audit_log WHERE act='note.export'", String.class);
        assertThat(target).isEqualTo("n1 (기타)");
    }

    @Test
    void exportLog_unreadableNote_isForbidden_andNotLogged() throws Exception {
        MockHttpSession s = login("20002"); // u2: n1 권한 없음
        mvc.perform(post("/api/nodes/n1/export-log").session(s).contentType(APPLICATION_JSON)
                .content("{\"format\":\"md\"}"))
            .andExpect(status().isForbidden());
        assertThat(auditCount("note.export")).isZero();
    }

    // ---- attachment.download ----

    private String upload(MockHttpSession s, String name) throws Exception {
        String json = mvc.perform(multipart("/api/nodes/n1/attachments").file(
                new MockMultipartFile("file", name, null, new byte[]{1, 2, 3})).session(s))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(json, "$.id");
    }

    @Test
    void download_nonImage_recordsAttachmentDownloadAudit() throws Exception {
        MockHttpSession s = login("10001");
        String id = upload(s, "doc.pdf");
        mvc.perform(get("/api/attachments/" + id).session(s)).andExpect(status().isOk());
        assertThat(auditCount("attachment.download")).isEqualTo(1);
        String target = jdbc.queryForObject("SELECT target FROM audit_log WHERE act='attachment.download'", String.class);
        assertThat(target).isEqualTo(id + " -> n1");
    }

    @Test
    void download_image_isNotLogged() throws Exception {
        MockHttpSession s = login("10001");
        String id = upload(s, "a.png"); // 이미지 = 인라인 프리뷰 → 감사 제외
        mvc.perform(get("/api/attachments/" + id).session(s)).andExpect(status().isOk());
        assertThat(auditCount("attachment.download")).isZero();
    }
}
