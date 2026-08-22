package com.worknote.template;

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

/** 사용자 템플릿 API — 가시성(시스템+내 것), 소유권(타인·시스템 403), 상한(422). server 모드. */
@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:sqlite:file:tplmem?mode=memory&cache=shared",
    "worknote.mode=server",
    "worknote.admin-password=boot-pass-1"
})
@AutoConfigureMockMvc
class NoteTemplateApiTest {

    @Autowired MockMvc mvc;
    @Autowired UserMapper users;
    @Autowired JdbcTemplate jdbc;
    private final ObjectMapper json = new ObjectMapper();

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM note_template WHERE owner_id IS NOT NULL");
        jdbc.update("DELETE FROM user_credential WHERE user_id <> 'u-admin'");
        jdbc.update("DELETE FROM app_user WHERE id <> 'u-admin'");
        createUser("u1", "10001");
        createUser("u2", "10002");
    }

    private void createUser(String id, String emp) {
        users.insert(new UserRow(id, emp, null, "이름-" + emp, "operator", "active", null));
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

    private String body(String name, String md) throws Exception {
        return json.writeValueAsString(java.util.Map.of("name", name, "body", md));
    }

    /** 템플릿을 만들고 id를 돌려준다. */
    private String create(MockHttpSession s, String name, String md) throws Exception {
        String res = mvc.perform(post("/api/templates").session(s).contentType(APPLICATION_JSON)
                .content(body(name, md)))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        return json.readTree(res).get("id").asText();
    }

    @Test
    void list_containsSystemSeedsAndOwnOnly() throws Exception {
        MockHttpSession s1 = login("10001");
        create(s1, "내 양식", "## 내 양식\n");
        create(login("10002"), "남의 양식", "## 남의 양식\n");

        mvc.perform(get("/api/templates").session(s1))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[*].name", hasItem("회의록")))
            .andExpect(jsonPath("$[*].name", hasItem("내 양식")))
            .andExpect(jsonPath("$[?(@.name == '남의 양식')]").isEmpty());
    }

    @Test
    void systemSeed_isMarkedSystemTrue() throws Exception {
        mvc.perform(get("/api/templates").session(login("10001")))
            .andExpect(jsonPath("$[?(@.name == '회의록')].system").value(hasItem(true)));
    }

    @Test
    void create_thenUpdate_persists() throws Exception {
        MockHttpSession s = login("10001");
        String id = create(s, "배포 체크", "## 배포\n- \n");

        mvc.perform(put("/api/templates/" + id).session(s).contentType(APPLICATION_JSON)
                .content(body("배포 체크리스트", "## 배포\n- [ ] 태그\n")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.name").value("배포 체크리스트"))
            .andExpect(jsonPath("$.body").value("## 배포\n- [ ] 태그\n"))
            .andExpect(jsonPath("$.system").value(false));
    }

    @Test
    void delete_removesIt() throws Exception {
        MockHttpSession s = login("10001");
        String id = create(s, "임시", "## 임시\n");
        mvc.perform(delete("/api/templates/" + id).session(s)).andExpect(status().isNoContent());
        mvc.perform(get("/api/templates").session(s))
            .andExpect(jsonPath("$[?(@.name == '임시')]").isEmpty());
    }

    @Test
    void updateOthersTemplate_is403() throws Exception {
        String id = create(login("10002"), "남의 양식", "## 남\n");
        mvc.perform(put("/api/templates/" + id).session(login("10001")).contentType(APPLICATION_JSON)
                .content(body("가로채기", "## 해킹\n")))
            .andExpect(status().isForbidden());
    }

    @Test
    void updateSystemTemplate_is403() throws Exception {
        mvc.perform(put("/api/templates/tpl-meeting").session(login("10001")).contentType(APPLICATION_JSON)
                .content(body("회의록 수정", "## 바꿈\n")))
            .andExpect(status().isForbidden());
    }

    @Test
    void deleteSystemTemplate_is403() throws Exception {
        mvc.perform(delete("/api/templates/tpl-meeting").session(login("10001")))
            .andExpect(status().isForbidden());
    }

    @Test
    void unknownId_is404() throws Exception {
        mvc.perform(delete("/api/templates/no-such-id").session(login("10001")))
            .andExpect(status().isNotFound());
    }

    @Test
    void blankName_is422() throws Exception {
        mvc.perform(post("/api/templates").session(login("10001")).contentType(APPLICATION_JSON)
                .content(body("   ", "## 본문\n")))
            .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void tooLongName_is422() throws Exception {
        mvc.perform(post("/api/templates").session(login("10001")).contentType(APPLICATION_JSON)
                .content(body("가".repeat(51), "## 본문\n")))
            .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void emptyBody_is422() throws Exception {
        mvc.perform(post("/api/templates").session(login("10001")).contentType(APPLICATION_JSON)
                .content(body("이름", "   ")))
            .andExpect(status().isUnprocessableEntity());
    }

    // F1: Java String.trim()은 U+0020 이하만 제거해 EM SPACE(U+2003)를 못 거른다.
    @Test
    void emSpaceOnlyName_is422() throws Exception {
        mvc.perform(post("/api/templates").session(login("10001")).contentType(APPLICATION_JSON)
                .content(body("  ", "## 본문\n")))
            .andExpect(status().isUnprocessableEntity());
    }

    // F1: String.isBlank()는 Character.isWhitespace 기준이라 NBSP(U+00A0, Zs 범주)를 못 거른다.
    @Test
    void nbspOnlyBody_is422() throws Exception {
        mvc.perform(post("/api/templates").session(login("10001")).contentType(APPLICATION_JSON)
                .content(body("이름", "  ")))
            .andExpect(status().isUnprocessableEntity());
    }

    // F5: 서버의 body.length() > BODY_MAX 검사를 지워도 통과하는 공백 회귀 테스트였다.
    @Test
    void tooLongBody_is422() throws Exception {
        mvc.perform(post("/api/templates").session(login("10001")).contentType(APPLICATION_JSON)
                .content(body("이름", "본".repeat(100_001))))
            .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void perOwnerLimit_is422() throws Exception {
        MockHttpSession s = login("10001");
        // 상한 50개를 SQL로 미리 채운다 (API 50회 호출은 느리다)
        for (int i = 0; i < 50; i++) {
            jdbc.update("INSERT INTO note_template (id, owner_id, name, body, created_at, updated_at)"
                + " VALUES (?, 'u1', ?, '## x', '2026-08-18T10:00:00', '2026-08-18T10:00:00')",
                "bulk-" + i, "양식-" + i);
        }
        mvc.perform(post("/api/templates").session(s).contentType(APPLICATION_JSON)
                .content(body("51번째", "## 본문\n")))
            .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void anonymous_is401() throws Exception {
        mvc.perform(get("/api/templates")).andExpect(status().isUnauthorized());
    }
}
