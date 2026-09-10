package com.worknote.admin;

import static org.hamcrest.Matchers.hasItem;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.worknote.auth.CredentialRow;
import com.worknote.auth.PasswordHasher;
import com.worknote.auth.UserMapper;
import com.worknote.auth.UserRow;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;

/** 관리자 업로드 정책 API — GET/PUT /api/admin/settings/upload, 비admin 403 (server 모드). */
@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:sqlite:file:phase2mem?mode=memory&cache=shared",
    "worknote.mode=server",
    "worknote.admin-password=boot-pass-1"
})
@AutoConfigureMockMvc
class AdminSettingApiTest {

    @Autowired MockMvc mvc;
    @Autowired UserMapper users;
    @Autowired JdbcTemplate jdbc;

    private static final String SEED_EXT = "png,jpg,jpeg,gif,webp,pdf,docx,xlsx,pptx,txt,md,csv,zip";
    private static final String SEED_MAX = "26214400";

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM audit_log");
        jdbc.update("DELETE FROM user_credential WHERE user_id <> 'u-admin'");
        jdbc.update("DELETE FROM app_user WHERE id <> 'u-admin'");
        jdbc.update("UPDATE app_user SET role_id = 'admin', status = 'active' WHERE id = 'u-admin'");
        restoreSeed();
        createUser("u1", "10001", "visitor", "active");
    }

    @AfterEach
    void cleanup() {
        // 공유 인메모리 DB — PUT이 시드를 덮어쓰므로 형제 테스트 클래스 오염 방지
        restoreSeed();
    }

    private void restoreSeed() {
        jdbc.update("UPDATE app_setting SET value = ? WHERE key = 'upload.allowed_ext'", SEED_EXT);
        jdbc.update("UPDATE app_setting SET value = ? WHERE key = 'upload.max_bytes'", SEED_MAX);
    }

    private void createUser(String id, String emp, String roleId, String status) {
        users.insert(new UserRow(id, emp, null, "이름-" + emp, roleId, status, null));
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

    private MockHttpSession admin() throws Exception {
        return login("admin", "boot-pass-1");
    }

    @Test
    void getUploadPolicy_returnsSeed() throws Exception {
        mvc.perform(get("/api/admin/settings/upload").session(admin()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.allowedExt", hasItem("png")))
            .andExpect(jsonPath("$.maxBytes").value(26214400));
    }

    @Test
    void putUploadPolicy_persists() throws Exception {
        MockHttpSession s = admin();
        mvc.perform(put("/api/admin/settings/upload").session(s).contentType(APPLICATION_JSON)
                .content("{\"allowedExt\":[\"png\",\"svg\"],\"maxBytes\":5000}"))
            .andExpect(status().isNoContent());
        mvc.perform(get("/api/admin/settings/upload").session(s))
            .andExpect(jsonPath("$.allowedExt", hasItem("svg")))
            .andExpect(jsonPath("$.maxBytes").value(5000));
    }

    /** D-8: 화면 검증을 우회하는 API 직접 호출 경로도 형식을 어긴 값을 저장하지 못한다. */
    @Test
    void putUploadPolicy_rejectsMalformedExt_withReason() throws Exception {
        MockHttpSession s = admin();
        mvc.perform(put("/api/admin/settings/upload").session(s).contentType(APPLICATION_JSON)
                .content("{\"allowedExt\":[\"png\",\".BAD!\"],\"maxBytes\":5000}"))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("영문 소문자·숫자")));
        // 거부됐으니 시드가 그대로여야 한다 (부분 저장 없음)
        mvc.perform(get("/api/admin/settings/upload").session(s))
            .andExpect(jsonPath("$.maxBytes").value(26214400));
    }

    @Test
    void putUploadPolicy_rejectsOverServletLimit_withReason() throws Exception {
        MockHttpSession s = admin();
        mvc.perform(put("/api/admin/settings/upload").session(s).contentType(APPLICATION_JSON)
                .content("{\"allowedExt\":[\"png\"],\"maxBytes\":104857600000}"))   // 99999MB 오타
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("64MB")));
    }

    @Test
    void putUploadPolicy_rejectsZeroAndNegativeMax() throws Exception {
        MockHttpSession s = admin();
        for (String max : new String[] {"0", "-1"}) {
            mvc.perform(put("/api/admin/settings/upload").session(s).contentType(APPLICATION_JSON)
                    .content("{\"allowedExt\":[\"png\"],\"maxBytes\":" + max + "}"))
                .andExpect(status().isUnprocessableEntity());
        }
    }

    @Test
    void putUploadPolicy_normalizesDotAndCase() throws Exception {
        MockHttpSession s = admin();
        mvc.perform(put("/api/admin/settings/upload").session(s).contentType(APPLICATION_JSON)
                .content("{\"allowedExt\":[\".PNG\",\" Jpg \"],\"maxBytes\":5000}"))
            .andExpect(status().isNoContent());
        mvc.perform(get("/api/admin/settings/upload").session(s))
            .andExpect(jsonPath("$.allowedExt", hasItem("png")))
            .andExpect(jsonPath("$.allowedExt", hasItem("jpg")));
    }

    @Test
    void nonAdmin_is403() throws Exception {
        mvc.perform(get("/api/admin/settings/upload").session(login("10001", "pw-1234")))
            .andExpect(status().isForbidden());
    }
}
