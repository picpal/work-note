package com.worknote.admin;

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
class AdminSpaceApiTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM audit_log");
        jdbc.update("DELETE FROM acl");
        jdbc.update("DELETE FROM space");
        jdbc.update("DELETE FROM team");
        jdbc.update("DELETE FROM node");
        jdbc.update("DELETE FROM user_credential WHERE user_id <> 'u-admin'");
        jdbc.update("DELETE FROM app_user WHERE id <> 'u-admin'");
        jdbc.update("INSERT INTO node (id, type, name, position) VALUES ('f1','folder','팀폴더',1)");
        jdbc.update("INSERT INTO node (id, parent_id, type, name, position) VALUES ('f2','f1','folder','하위',1)");
        jdbc.update("INSERT INTO node (id, type, name, position) VALUES ('n1','note','루트노트',2)");
        jdbc.update("INSERT INTO team (id, name) VALUES ('t1','결제팀')");
    }

    private MockHttpSession admin() throws Exception {
        MockHttpSession session = new MockHttpSession();
        mvc.perform(post("/api/auth/login").session(session).contentType(APPLICATION_JSON)
                .content("{\"emp\":\"admin\",\"password\":\"boot-pass-1\"}"))
            .andExpect(status().isOk());
        return session;
    }

    @Test
    void put_assignsTeam_andAutoGrantsEdit() throws Exception {
        mvc.perform(put("/api/admin/spaces/f1").session(admin()).contentType(APPLICATION_JSON)
                .content("{\"teamId\":\"t1\"}"))
            .andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject("SELECT team_id FROM space WHERE node_id = 'f1'", String.class)).isEqualTo("t1");
        assertThat(jdbc.queryForObject(
            "SELECT COUNT(*) FROM acl WHERE principal_type='team' AND principal_id='t1' AND node_id='f1' AND grant_type='edit'",
            Integer.class)).isEqualTo(1);
    }

    @Test
    void put_isUpsert_andKeepsExistingGrant() throws Exception {
        MockHttpSession s = admin();
        jdbc.update("INSERT INTO acl (principal_type, principal_id, node_id, grant_type) VALUES ('team','t1','f1','read')");
        mvc.perform(put("/api/admin/spaces/f1").session(s).contentType(APPLICATION_JSON)
                .content("{\"teamId\":\"t1\"}")).andExpect(status().isNoContent());
        // 이미 그 팀 grant가 있으면 덮어쓰지 않음 (관리자가 의도적으로 read로 낮춘 상태 존중)
        assertThat(jdbc.queryForObject(
            "SELECT grant_type FROM acl WHERE principal_type='team' AND principal_id='t1' AND node_id='f1'",
            String.class)).isEqualTo("read");
        // 공용 전환 upsert
        mvc.perform(put("/api/admin/spaces/f1").session(s).contentType(APPLICATION_JSON)
                .content("{}")).andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject("SELECT team_id FROM space WHERE node_id = 'f1'", String.class)).isNull();
    }

    @Test
    void put_nonTopLevelOrNote_422() throws Exception {
        MockHttpSession s = admin();
        mvc.perform(put("/api/admin/spaces/f2").session(s).contentType(APPLICATION_JSON).content("{}"))
            .andExpect(status().isUnprocessableEntity());
        mvc.perform(put("/api/admin/spaces/n1").session(s).contentType(APPLICATION_JSON).content("{}"))
            .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void put_unknownNode_404_unknownTeam_422() throws Exception {
        MockHttpSession s = admin();
        mvc.perform(put("/api/admin/spaces/no-such").session(s).contentType(APPLICATION_JSON).content("{}"))
            .andExpect(status().isNotFound());
        mvc.perform(put("/api/admin/spaces/f1").session(s).contentType(APPLICATION_JSON)
                .content("{\"teamId\":\"no-such\"}"))
            .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void delete_removes_unknown_404() throws Exception {
        MockHttpSession s = admin();
        mvc.perform(put("/api/admin/spaces/f1").session(s).contentType(APPLICATION_JSON).content("{}"))
            .andExpect(status().isNoContent());
        mvc.perform(delete("/api/admin/spaces/f1").session(s)).andExpect(status().isNoContent());
        mvc.perform(delete("/api/admin/spaces/f1").session(s)).andExpect(status().isNotFound());
    }

    @Test
    void list_returnsRows() throws Exception {
        MockHttpSession s = admin();
        mvc.perform(put("/api/admin/spaces/f1").session(s).contentType(APPLICATION_JSON)
                .content("{\"teamId\":\"t1\"}")).andExpect(status().isNoContent());
        mvc.perform(get("/api/admin/spaces").session(s))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].nodeId").value("f1"))
            .andExpect(jsonPath("$[0].teamId").value("t1"));
    }
}
