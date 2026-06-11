package com.worknote.admin;

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

import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:sqlite:file:phase2mem?mode=memory&cache=shared",
    "worknote.mode=server",
    "worknote.admin-password=boot-pass-1"
})
@AutoConfigureMockMvc
class AdminRoleApiTest {

    @Autowired MockMvc mvc;
    @Autowired UserMapper users;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM audit_log");
        jdbc.update("DELETE FROM role WHERE system = 0");
        jdbc.update("DELETE FROM user_credential WHERE user_id <> 'u-admin'");
        jdbc.update("DELETE FROM app_user WHERE id <> 'u-admin'");
        jdbc.update("UPDATE app_user SET role_id = 'admin', status = 'active' WHERE id = 'u-admin'");
    }

    @AfterEach
    void restoreAdmin() {
        // lastAdminRole 테스트가 u-admin을 disabled로 바꾸므로 실패 시에도 원복 보장 — 공유 인메모리 DB라 형제 테스트 클래스 오염 방지
        jdbc.update("UPDATE app_user SET role_id = 'admin', status = 'active' WHERE id = 'u-admin'");
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
    void list_includesSeedRolesWithUserCount() throws Exception {
        mvc.perform(get("/api/admin/roles").session(admin()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[?(@.id=='admin')].userCount").value(1))
            .andExpect(jsonPath("$[?(@.id=='admin')].system").value(true));
    }

    @Test
    void create_patch_delete_roundTrip() throws Exception {
        MockHttpSession s = admin();
        mvc.perform(post("/api/admin/roles").session(s).contentType(APPLICATION_JSON)
                .content("{\"id\":\"editor\",\"name\":\"편집자\",\"caps\":[\"res.read\",\"res.edit\"]}"))
            .andExpect(status().isCreated());
        mvc.perform(patch("/api/admin/roles/editor").session(s).contentType(APPLICATION_JSON)
                .content("{\"caps\":[\"res.read\"]}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.caps.length()").value(1));
        mvc.perform(delete("/api/admin/roles/editor").session(s)).andExpect(status().isNoContent());
    }

    @Test
    void patch_blankName_422() throws Exception {
        MockHttpSession s = admin();
        mvc.perform(post("/api/admin/roles").session(s).contentType(APPLICATION_JSON)
                .content("{\"id\":\"editor\",\"name\":\"편집자\",\"caps\":[\"res.read\"]}"))
            .andExpect(status().isCreated());
        mvc.perform(patch("/api/admin/roles/editor").session(s).contentType(APPLICATION_JSON)
                .content("{\"name\":\"\"}"))
            .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void unknownCap_422() throws Exception {
        mvc.perform(post("/api/admin/roles").session(admin()).contentType(APPLICATION_JSON)
                .content("{\"id\":\"bad\",\"name\":\"x\",\"caps\":[\"res.raed\"]}"))
            .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void systemRole_patchOrDelete_422() throws Exception {
        MockHttpSession s = admin();
        mvc.perform(patch("/api/admin/roles/admin").session(s).contentType(APPLICATION_JSON)
                .content("{\"name\":\"바꿈\"}"))
            .andExpect(status().isUnprocessableEntity());
        mvc.perform(delete("/api/admin/roles/visitor").session(s)).andExpect(status().isUnprocessableEntity());
    }

    @Test
    void roleInUse_delete_409() throws Exception {
        MockHttpSession s = admin();
        mvc.perform(post("/api/admin/roles").session(s).contentType(APPLICATION_JSON)
                .content("{\"id\":\"editor\",\"name\":\"편집자\",\"caps\":[\"res.read\"]}"))
            .andExpect(status().isCreated());
        jdbc.update("INSERT INTO app_user (id, emp, name, role_id, status) VALUES ('ux','99999','x','editor','active')");
        mvc.perform(delete("/api/admin/roles/editor").session(s)).andExpect(status().isConflict());
    }

    @Test
    void duplicateId_409() throws Exception {
        mvc.perform(post("/api/admin/roles").session(admin()).contentType(APPLICATION_JSON)
                .content("{\"id\":\"admin\",\"name\":\"x\",\"caps\":[\"res.read\"]}"))
            .andExpect(status().isConflict());
    }

    @Test
    void removingAdminCaps_fromLastAdminRole_422() throws Exception {
        // admin caps를 가진 커스텀 역할 — 이 역할만 쓰는 마지막 활성 관리자의 락아웃 우회 경로 차단 검증
        MockHttpSession s = admin();
        mvc.perform(post("/api/admin/roles").session(s).contentType(APPLICATION_JSON)
                .content("{\"id\":\"super\",\"name\":\"슈퍼\",\"caps\":[\"admin.users\",\"admin.permissions\","
                    + "\"admin.roles\",\"admin.security\",\"admin.audit\",\"res.read\"]}"))
            .andExpect(status().isCreated());
        createUser("u9", "90009", "super", "active");
        MockHttpSession s9 = login("90009", "pw-1234");
        // u-admin 비활성화 → 활성 관리자는 super 역할의 u9뿐
        jdbc.update("UPDATE app_user SET status = 'disabled' WHERE id = 'u-admin'");
        mvc.perform(patch("/api/admin/roles/super").session(s9).contentType(APPLICATION_JSON)
                .content("{\"caps\":[\"res.read\"]}"))
            .andExpect(status().isUnprocessableEntity());
    }
}
