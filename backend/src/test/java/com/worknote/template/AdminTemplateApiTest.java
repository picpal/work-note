package com.worknote.template;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
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

/** 관리자 시스템 템플릿 API — 비관리자 403, 개인 템플릿 침범 금지, 감사 기록. */
@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:sqlite:file:tplmem?mode=memory&cache=shared",
    "worknote.mode=server",
    "worknote.admin-password=boot-pass-1"
})
@AutoConfigureMockMvc
class AdminTemplateApiTest {

    @Autowired MockMvc mvc;
    @Autowired UserMapper users;
    @Autowired JdbcTemplate jdbc;
    private final ObjectMapper json = new ObjectMapper();

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM audit_log");
        jdbc.update("DELETE FROM note_template WHERE id NOT LIKE 'tpl-%'");
        jdbc.update("DELETE FROM user_credential WHERE user_id <> 'u-admin'");
        jdbc.update("DELETE FROM app_user WHERE id <> 'u-admin'");
        jdbc.update("UPDATE app_user SET role_id = 'admin', status = 'active' WHERE id = 'u-admin'");
        users.insert(new UserRow("u1", "10001", null, "일반", "operator", "active", null));
        String salt = PasswordHasher.newSalt();
        users.insertCredential(new CredentialRow("u1", salt, PasswordHasher.hash("pw-1234", salt)));
    }

    private MockHttpSession login(String emp, String pw) throws Exception {
        MockHttpSession session = new MockHttpSession();
        mvc.perform(post("/api/auth/login").session(session).contentType(APPLICATION_JSON)
                .content("{\"emp\":\"" + emp + "\",\"password\":\"" + pw + "\"}"))
            .andExpect(status().isOk());
        return session;
    }

    private MockHttpSession admin() throws Exception {
        return login("admin", "boot-pass-1");
    }

    private String body(String name, String md) throws Exception {
        return json.writeValueAsString(java.util.Map.of("name", name, "body", md));
    }

    @Test
    void list_returnsOnlySystemTemplates() throws Exception {
        jdbc.update("INSERT INTO note_template (id, owner_id, name, body, created_at, updated_at)"
            + " VALUES ('mine-1', 'u1', '개인 양식', '## x', '2026-08-18T10:00:00', '2026-08-18T10:00:00')");

        mvc.perform(get("/api/admin/templates").session(admin()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[*].name", hasItem("회의록")))
            .andExpect(jsonPath("$[?(@.name == '개인 양식')]").isEmpty());
    }

    @Test
    void create_makesSystemTemplateVisibleToEveryone() throws Exception {
        mvc.perform(post("/api/admin/templates").session(admin()).contentType(APPLICATION_JSON)
                .content(body("보안 점검표", "## 점검\n- \n")))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.system").value(true));

        // 일반 사용자에게도 보인다
        mvc.perform(get("/api/templates").session(login("10001", "pw-1234")))
            .andExpect(jsonPath("$[*].name", hasItem("보안 점검표")));
    }

    /** 시드(tpl-*)는 건드리지 않는다 — tplmem은 형제 테스트 클래스와 공유하는 DB이고,
        NoteTemplateMapperTest가 시드 3종의 존재를 단언한다. 만든 것만 고치고 지운다. */
    private String createSystem(MockHttpSession s, String name) throws Exception {
        String res = mvc.perform(post("/api/admin/templates").session(s).contentType(APPLICATION_JSON)
                .content(body(name, "## " + name + "\n- \n")))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        return json.readTree(res).get("id").asText();
    }

    @Test
    void update_and_delete_systemTemplate() throws Exception {
        MockHttpSession s = admin();
        String id = createSystem(s, "배포 절차");

        mvc.perform(put("/api/admin/templates/" + id).session(s).contentType(APPLICATION_JSON)
                .content(body("배포 절차(개정)", "## 배포\n- \n")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.name").value("배포 절차(개정)"))
            .andExpect(jsonPath("$.system").value(true));

        mvc.perform(delete("/api/admin/templates/" + id).session(s))
            .andExpect(status().isNoContent());
        mvc.perform(get("/api/admin/templates").session(s))
            .andExpect(jsonPath("$[?(@.id == '" + id + "')]").isEmpty());
    }

    @Test
    void nonAdmin_is403() throws Exception {
        mvc.perform(get("/api/admin/templates").session(login("10001", "pw-1234")))
            .andExpect(status().isForbidden());
        mvc.perform(post("/api/admin/templates").session(login("10001", "pw-1234"))
                .contentType(APPLICATION_JSON).content(body("몰래", "## x\n")))
            .andExpect(status().isForbidden());
    }

    @Test
    void adminCannotEditPersonalTemplateThroughAdminPath() throws Exception {
        jdbc.update("INSERT INTO note_template (id, owner_id, name, body, created_at, updated_at)"
            + " VALUES ('mine-1', 'u1', '개인 양식', '## x', '2026-08-18T10:00:00', '2026-08-18T10:00:00')");

        mvc.perform(put("/api/admin/templates/mine-1").session(admin()).contentType(APPLICATION_JSON)
                .content(body("가로채기", "## x\n")))
            .andExpect(status().isForbidden());
    }

    @Test
    void writes_areAudited() throws Exception {
        MockHttpSession s = admin();
        String id = createSystem(s, "감사용");
        mvc.perform(delete("/api/admin/templates/" + id).session(s))
            .andExpect(status().isNoContent());

        var acts = jdbc.queryForList("SELECT act FROM audit_log ORDER BY id", String.class);
        assertThat(acts).contains("template.system.create", "template.system.delete");

        String target = jdbc.queryForObject(
            "SELECT target FROM audit_log WHERE act = 'template.system.create'", String.class);
        assertThat(target).isEqualTo("감사용");
    }

    @Test
    void unknownId_is404() throws Exception {
        mvc.perform(delete("/api/admin/templates/no-such-id").session(admin()))
            .andExpect(status().isNotFound());
    }
}
