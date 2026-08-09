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

import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * M-1 감사 델타 — 권한 변경이 "누가 무슨 권한을 받았는지"까지 재구성 가능하게 기록되는지 end-to-end 검증.
 * target(사람이 읽는 라벨)은 기존 계약 그대로 유지되고 델타는 detail 컬럼에만 들어간다.
 */
@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:sqlite:file:auditdeltamem?mode=memory&cache=shared",
    "worknote.mode=server",
    "worknote.admin-password=boot-pass-1"
})
@AutoConfigureMockMvc
class AdminAuditDeltaApiTest {

    @Autowired MockMvc mvc;
    @Autowired UserMapper users;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void seed() {
        jdbc.update("DELETE FROM audit_log");
        jdbc.update("DELETE FROM acl");
        jdbc.update("DELETE FROM public_flag");
        jdbc.update("DELETE FROM team");
        jdbc.update("DELETE FROM node");
        jdbc.update("DELETE FROM role WHERE id = 'r-tmp'");
        jdbc.update("DELETE FROM user_credential WHERE user_id <> 'u-admin'");
        jdbc.update("DELETE FROM app_user WHERE id <> 'u-admin'");
        users.insert(new UserRow("u1", "10001", null, "홍길동", "operator", "active", null));
        String salt = PasswordHasher.newSalt();
        users.insertCredential(new CredentialRow("u1", salt, PasswordHasher.hash("pw-1234", salt)));
        jdbc.update("INSERT INTO team (id, name) VALUES ('t1','결제팀')");
        jdbc.update("INSERT INTO node (id, type, name, position) VALUES ('f1','folder','F1',1)");
    }

    private MockHttpSession admin() throws Exception {
        MockHttpSession session = new MockHttpSession();
        mvc.perform(post("/api/auth/login").session(session).contentType(APPLICATION_JSON)
                .content("{\"emp\":\"admin\",\"password\":\"boot-pass-1\"}"))
            .andExpect(status().isOk());
        return session;
    }

    private String lastDetail(String act) {
        return jdbc.queryForObject(
            "SELECT detail FROM audit_log WHERE act = ? ORDER BY id DESC LIMIT 1", String.class, act);
    }

    private void putAcl(MockHttpSession s, String body) throws Exception {
        mvc.perform(put("/api/admin/nodes/f1/acl").session(s).contentType(APPLICATION_JSON).content(body))
            .andExpect(status().isNoContent());
    }

    @Test
    void aclSet_recordsAddedRemovedChanged() throws Exception {
        MockHttpSession admin = admin();
        putAcl(admin, "{\"entries\":[{\"principalType\":\"user\",\"principalId\":\"u1\",\"grantType\":\"edit\"},"
            + "{\"principalType\":\"team\",\"principalId\":\"t1\",\"grantType\":\"read\"}]}");
        assertThat(lastDetail("acl.set")).isEqualTo(
            "{\"added\":[{\"p\":\"team:t1\",\"g\":\"read\"},{\"p\":\"user:u1\",\"g\":\"edit\"}]}");

        // u1 회수 + t1 강등 — 되돌림이 재구성 가능해야 한다
        putAcl(admin, "{\"entries\":[{\"principalType\":\"team\",\"principalId\":\"t1\",\"grantType\":\"deny\"}]}");
        assertThat(lastDetail("acl.set")).isEqualTo(
            "{\"removed\":[{\"p\":\"user:u1\",\"g\":\"edit\"}],"
                + "\"changed\":[{\"p\":\"team:t1\",\"from\":\"read\",\"to\":\"deny\"}]}");

        // target 라벨 계약은 그대로 (감사 화면·월간 리포트가 이미 소비 중)
        assertThat(jdbc.queryForObject(
            "SELECT target FROM audit_log WHERE act = 'acl.set' ORDER BY id DESC LIMIT 1", String.class))
            .isEqualTo("f1 (1건)");
    }

    @Test
    void aclSet_noChange_leavesDetailNull() throws Exception {
        MockHttpSession admin = admin();
        String body = "{\"entries\":[{\"principalType\":\"user\",\"principalId\":\"u1\",\"grantType\":\"read\"}]}";
        putAcl(admin, body);
        putAcl(admin, body);
        assertThat(lastDetail("acl.set")).isNull();
    }

    @Test
    void aclSet_tooManyEntries_400_andNothingChanged() throws Exception {
        MockHttpSession admin = admin();
        putAcl(admin, "{\"entries\":[{\"principalType\":\"user\",\"principalId\":\"u1\",\"grantType\":\"read\"}]}");
        String oversize = IntStream.rangeClosed(0, com.worknote.admin.dto.SetAclRequest.MAX_ENTRIES)
            .mapToObj(i -> "{\"principalType\":\"user\",\"principalId\":\"u" + i + "\",\"grantType\":\"read\"}")
            .collect(Collectors.joining(",", "{\"entries\":[", "]}"));
        mvc.perform(put("/api/admin/nodes/f1/acl").session(admin).contentType(APPLICATION_JSON).content(oversize))
            .andExpect(status().isBadRequest());
        // 상한 초과는 변경도 기록 누락도 없다 — 절단(증거 인멸)을 쓰지 않는 이유
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM acl WHERE node_id = 'f1'", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE act = 'acl.set'", Integer.class))
            .isEqualTo(1);
    }

    @Test
    void roleUpdate_recordsNameAndCapsDelta() throws Exception {
        MockHttpSession admin = admin();
        mvc.perform(post("/api/admin/roles").session(admin).contentType(APPLICATION_JSON)
                .content("{\"id\":\"r-tmp\",\"name\":\"검토자\",\"caps\":[\"res.read\",\"res.export\"]}"))
            .andExpect(status().isCreated());
        mvc.perform(patch("/api/admin/roles/r-tmp").session(admin).contentType(APPLICATION_JSON)
                .content("{\"name\":\"리뷰어\",\"caps\":[\"res.read\",\"res.delete\"]}"))
            .andExpect(status().isOk());
        assertThat(lastDetail("role.update")).isEqualTo(
            "{\"name\":{\"from\":\"검토자\",\"to\":\"리뷰어\"},"
                + "\"caps\":{\"added\":[\"res.delete\"],\"removed\":[\"res.export\"]}}");
    }

    @Test
    void publicSetAndUnset_recordPreviousMode() throws Exception {
        MockHttpSession admin = admin();
        mvc.perform(put("/api/admin/nodes/f1/public").session(admin).contentType(APPLICATION_JSON)
                .content("{\"mode\":\"public\"}"))
            .andExpect(status().isNoContent());
        assertThat(lastDetail("public.set")).isEqualTo("{\"from\":null,\"to\":\"public\"}");

        mvc.perform(put("/api/admin/nodes/f1/public").session(admin).contentType(APPLICATION_JSON)
                .content("{\"mode\":\"exclude\"}"))
            .andExpect(status().isNoContent());
        assertThat(lastDetail("public.set")).isEqualTo("{\"from\":\"public\",\"to\":\"exclude\"}");

        mvc.perform(delete("/api/admin/nodes/f1/public").session(admin))
            .andExpect(status().isNoContent());
        assertThat(lastDetail("public.unset")).isEqualTo("{\"from\":\"exclude\",\"to\":null}");
    }

    @Test
    void auditListApi_exposesDetail() throws Exception {
        MockHttpSession admin = admin();
        putAcl(admin, "{\"entries\":[{\"principalType\":\"team\",\"principalId\":\"t1\",\"grantType\":\"read\"}]}");
        mvc.perform(get("/api/admin/audit").param("act", "acl.set").session(admin))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.rows[0].detail")
                .value("{\"added\":[{\"p\":\"team:t1\",\"g\":\"read\"}]}"));
    }

    @Test
    void nonPermissionActs_keepDetailNull() throws Exception {
        MockHttpSession admin = admin();
        mvc.perform(post("/api/admin/roles").session(admin).contentType(APPLICATION_JSON)
                .content("{\"id\":\"r-tmp\",\"name\":\"검토자\",\"caps\":[\"res.read\"]}"))
            .andExpect(status().isCreated());
        assertThat(lastDetail("role.create")).isNull();
        assertThat(lastDetail("login.success")).isNull();
    }
}
