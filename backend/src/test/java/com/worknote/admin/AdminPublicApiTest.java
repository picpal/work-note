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
class AdminPublicApiTest {

    @Autowired MockMvc mvc;
    @Autowired UserMapper users;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM audit_log");
        jdbc.update("DELETE FROM acl");
        jdbc.update("DELETE FROM public_flag");
        jdbc.update("DELETE FROM node");
        jdbc.update("DELETE FROM user_credential WHERE user_id <> 'u-admin'");
        jdbc.update("DELETE FROM app_user WHERE id <> 'u-admin'");
        users.insert(new UserRow("u1", "10001", null, "방문자", "visitor", "active", null));
        String salt = PasswordHasher.newSalt();
        users.insertCredential(new CredentialRow("u1", salt, PasswordHasher.hash("pw-1234", salt)));
        jdbc.update("INSERT INTO node (id, type, name, position) VALUES ('f1','folder','공개폴더',1)");
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
    void setPublic_visitorCanRead_unset_revokes() throws Exception {
        MockHttpSession admin = login("admin", "boot-pass-1");
        MockHttpSession visitor = login("10001", "pw-1234");
        mvc.perform(get("/api/tree").session(visitor)).andExpect(jsonPath("$.length()").value(0));
        mvc.perform(put("/api/admin/nodes/f1/public").session(admin).contentType(APPLICATION_JSON)
                .content("{\"mode\":\"public\"}"))
            .andExpect(status().isNoContent());
        mvc.perform(get("/api/tree").session(visitor)).andExpect(jsonPath("$.length()").value(1));
        mvc.perform(delete("/api/admin/nodes/f1/public").session(admin)).andExpect(status().isNoContent());
        mvc.perform(get("/api/tree").session(visitor)).andExpect(jsonPath("$.length()").value(0));
        assertThat(jdbc.queryForObject(
            "SELECT COUNT(*) FROM audit_log WHERE act = 'public.set' AND target = 'f1 public'",
            Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
            "SELECT COUNT(*) FROM audit_log WHERE act = 'public.unset' AND target = 'f1'",
            Integer.class)).isEqualTo(1);
    }

    @Test
    void setPublic_isUpsert() throws Exception {
        MockHttpSession admin = login("admin", "boot-pass-1");
        mvc.perform(put("/api/admin/nodes/n1/public").session(admin).contentType(APPLICATION_JSON)
                .content("{\"mode\":\"public\"}")).andExpect(status().isNoContent());
        mvc.perform(put("/api/admin/nodes/n1/public").session(admin).contentType(APPLICATION_JSON)
                .content("{\"mode\":\"exclude\"}")).andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject("SELECT mode FROM public_flag WHERE node_id = 'n1'", String.class))
            .isEqualTo("exclude");
    }

    @Test
    void invalidMode_400_unknownNode_404_unsetMissing_404() throws Exception {
        MockHttpSession admin = login("admin", "boot-pass-1");
        mvc.perform(put("/api/admin/nodes/f1/public").session(admin).contentType(APPLICATION_JSON)
                .content("{\"mode\":\"open\"}")).andExpect(status().isBadRequest());
        mvc.perform(put("/api/admin/nodes/no-such/public").session(admin).contentType(APPLICATION_JSON)
                .content("{\"mode\":\"public\"}")).andExpect(status().isNotFound());
        mvc.perform(delete("/api/admin/nodes/f1/public").session(admin)).andExpect(status().isNotFound());
    }

    @Test
    void createNoteUnderPublicFolder_autoExcluded() throws Exception {
        MockHttpSession admin = login("admin", "boot-pass-1");
        mvc.perform(put("/api/admin/nodes/f1/public").session(admin).contentType(APPLICATION_JSON)
                .content("{\"mode\":\"public\"}")).andExpect(status().isNoContent());
        // public 폴더 아래 새 노트 → 자동 exclude (스펙 §7)
        mvc.perform(post("/api/nodes").session(admin).contentType(APPLICATION_JSON)
                .content("{\"id\":\"n2\",\"parentId\":\"f1\",\"type\":\"note\",\"name\":\"새노트\"}"))
            .andExpect(status().isCreated());
        assertThat(jdbc.queryForObject("SELECT mode FROM public_flag WHERE node_id = 'n2'", String.class))
            .isEqualTo("exclude");
        // 방문자에겐 기존 n1은 보이고 새 노트는 안 보인다
        MockHttpSession visitor = login("10001", "pw-1234");
        mvc.perform(get("/api/tree").session(visitor))
            .andExpect(jsonPath("$[0].children.length()").value(1));
        // 폴더 생성은 exclude를 박지 않는다 (cascade 유지)
        mvc.perform(post("/api/nodes").session(admin).contentType(APPLICATION_JSON)
                .content("{\"id\":\"f2\",\"parentId\":\"f1\",\"type\":\"folder\",\"name\":\"하위폴더\"}"))
            .andExpect(status().isCreated());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM public_flag WHERE node_id = 'f2'", Integer.class))
            .isZero();
    }

    @Test
    void createNoteUnderExcludedSubfolder_noAutoExclude() throws Exception {
        // nearest-flag 의미 고정: public f1 > exclude f2 체인에선 f2가 더 가까워 노출이 없으니
        // 새 노트에 exclude를 박을 이유도 없다 (스펙 §7 — 자동 exclude는 노출되는 경우에만)
        MockHttpSession admin = login("admin", "boot-pass-1");
        mvc.perform(put("/api/admin/nodes/f1/public").session(admin).contentType(APPLICATION_JSON)
                .content("{\"mode\":\"public\"}")).andExpect(status().isNoContent());
        mvc.perform(post("/api/nodes").session(admin).contentType(APPLICATION_JSON)
                .content("{\"id\":\"f2\",\"parentId\":\"f1\",\"type\":\"folder\",\"name\":\"비공개하위\"}"))
            .andExpect(status().isCreated());
        mvc.perform(put("/api/admin/nodes/f2/public").session(admin).contentType(APPLICATION_JSON)
                .content("{\"mode\":\"exclude\"}")).andExpect(status().isNoContent());
        mvc.perform(post("/api/nodes").session(admin).contentType(APPLICATION_JSON)
                .content("{\"id\":\"n4\",\"parentId\":\"f2\",\"type\":\"note\",\"name\":\"숨은노트\"}"))
            .andExpect(status().isCreated());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM public_flag WHERE node_id = 'n4'", Integer.class))
            .isZero();
    }

    @Test
    void createNoteUnderPrivateFolder_noExcludeRow() throws Exception {
        MockHttpSession admin = login("admin", "boot-pass-1");
        mvc.perform(post("/api/nodes").session(admin).contentType(APPLICATION_JSON)
                .content("{\"id\":\"n3\",\"parentId\":\"f1\",\"type\":\"note\",\"name\":\"비공개\"}"))
            .andExpect(status().isCreated());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM public_flag WHERE node_id = 'n3'", Integer.class))
            .isZero();
    }
}
