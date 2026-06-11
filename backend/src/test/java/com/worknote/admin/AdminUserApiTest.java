package com.worknote.admin;

import com.worknote.auth.CredentialRow;
import com.worknote.auth.PasswordHasher;
import com.worknote.auth.UserMapper;
import com.worknote.auth.UserRow;
import com.worknote.vault.VaultException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:sqlite:file:phase2mem?mode=memory&cache=shared",
    "worknote.mode=server",
    "worknote.admin-password=boot-pass-1"
})
@AutoConfigureMockMvc
class AdminUserApiTest {

    @Autowired MockMvc mvc;
    @Autowired UserMapper users;
    @Autowired UserAdminService svc;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM audit_log");
        jdbc.update("DELETE FROM user_credential WHERE user_id <> 'u-admin'");
        jdbc.update("DELETE FROM app_user WHERE id <> 'u-admin'");
        // demote 테스트가 u-admin 역할을 바꾸므로 복원 — 공유 인메모리 DB라 다른 테스트로 누수 방지
        jdbc.update("UPDATE app_user SET role_id = 'admin', status = 'active' WHERE id = 'u-admin'");
        createUser("u1", "10001", "operator", "active");
        createUser("u2", "20002", "visitor", "pending");
    }

    @AfterEach
    void restoreAdmin() {
        // demote 테스트가 u-admin 역할을 바꾸므로 실패 시에도 원복 보장 — 공유 인메모리 DB라 형제 테스트 클래스 오염 방지
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

    @Test
    void nonAdmin_403() throws Exception {
        MockHttpSession op = login("10001", "pw-1234");
        mvc.perform(get("/api/admin/users").session(op)).andExpect(status().isForbidden());
    }

    @Test
    void list_returnsAllUsers() throws Exception {
        MockHttpSession admin = login("admin", "boot-pass-1");
        mvc.perform(get("/api/admin/users").session(admin))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(3));
    }

    @Test
    void create_thenNewUserCanLogin() throws Exception {
        MockHttpSession admin = login("admin", "boot-pass-1");
        mvc.perform(post("/api/admin/users").session(admin).contentType(APPLICATION_JSON)
                .content("{\"emp\":\"30003\",\"name\":\"새사람\",\"roleId\":\"operator\",\"password\":\"pw-12345678\"}"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.status").value("active"));
        login("30003", "pw-12345678");
        assertThat(jdbc.queryForObject(
            "SELECT COUNT(*) FROM audit_log WHERE act = 'user.create' AND target = '30003'", Integer.class)).isEqualTo(1);
    }

    @Test
    void create_duplicateEmp_409_unknownRole_422() throws Exception {
        MockHttpSession admin = login("admin", "boot-pass-1");
        mvc.perform(post("/api/admin/users").session(admin).contentType(APPLICATION_JSON)
                .content("{\"emp\":\"10001\",\"name\":\"x\",\"roleId\":\"operator\",\"password\":\"pw-12345678\"}"))
            .andExpect(status().isConflict());
        mvc.perform(post("/api/admin/users").session(admin).contentType(APPLICATION_JSON)
                .content("{\"emp\":\"30003\",\"name\":\"x\",\"roleId\":\"no-such\",\"password\":\"pw-12345678\"}"))
            .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void approve_activatesPending_thenLogin() throws Exception {
        MockHttpSession admin = login("admin", "boot-pass-1");
        mvc.perform(post("/api/admin/users/u2/approve").session(admin))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("active"));
        login("20002", "pw-1234");
    }

    @Test
    void approve_nonPending_409() throws Exception {
        MockHttpSession admin = login("admin", "boot-pass-1");
        mvc.perform(post("/api/admin/users/u1/approve").session(admin)).andExpect(status().isConflict());
    }

    @Test
    void patch_roleAndStatus() throws Exception {
        MockHttpSession admin = login("admin", "boot-pass-1");
        mvc.perform(patch("/api/admin/users/u1").session(admin).contentType(APPLICATION_JSON)
                .content("{\"roleId\":\"visitor\",\"status\":\"disabled\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.roleId").value("visitor"))
            .andExpect(jsonPath("$.status").value("disabled"));
        mvc.perform(post("/api/auth/login").contentType(APPLICATION_JSON)
                .content("{\"emp\":\"10001\",\"password\":\"pw-1234\"}"))
            .andExpect(status().isForbidden());
    }

    @Test
    void patch_self_roleOrStatus_422() throws Exception {
        MockHttpSession admin = login("admin", "boot-pass-1");
        mvc.perform(patch("/api/admin/users/u-admin").session(admin).contentType(APPLICATION_JSON)
                .content("{\"status\":\"disabled\"}"))
            .andExpect(status().isUnprocessableEntity());
        mvc.perform(patch("/api/admin/users/u-admin").session(admin).contentType(APPLICATION_JSON)
                .content("{\"roleId\":\"visitor\"}"))
            .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void demoteLastActiveAdmin_byAnotherAdmin_422() throws Exception {
        // 관리자 2명 구성 후, 서로가 마지막 1명을 강등하려는 상황 재현
        createUser("u9", "90009", "admin", "active");
        MockHttpSession a2 = login("90009", "pw-1234");
        // u-admin 강등 → 남은 활성 admin은 u9뿐 — 허용
        mvc.perform(patch("/api/admin/users/u-admin").session(a2).contentType(APPLICATION_JSON)
                .content("{\"roleId\":\"operator\"}"))
            .andExpect(status().isOk());
        // 이제 u9가 마지막 활성 admin — HTTP 경로에선 본인 요청이라 self 규칙이 먼저 발화해 422
        // (last-admin 규칙 자체는 서비스 레벨 테스트 lastActiveAdmin_demote_rejected_serviceLevel이 직접 검증)
        mvc.perform(patch("/api/admin/users/u9").session(a2).contentType(APPLICATION_JSON)
                .content("{\"roleId\":\"operator\"}"))
            .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void lastActiveAdmin_demote_rejected_serviceLevel() {
        // u-admin이 유일한 활성 admin. server HTTP 경로에선 self 규칙에 선차단되어 도달 불가 — 서비스 직접 호출로 검증
        assertThatThrownBy(() -> svc.update(null, "u-admin", null, null, "operator", null))
            .isInstanceOf(VaultException.class)
            .satisfies(e -> assertThat(((VaultException) e).status()).isEqualTo(VaultException.Status.INVALID))
            .hasMessageContaining("마지막 활성 관리자");
    }

    @Test
    void patch_unknownUser_404() throws Exception {
        MockHttpSession admin = login("admin", "boot-pass-1");
        mvc.perform(patch("/api/admin/users/no-such").session(admin).contentType(APPLICATION_JSON)
                .content("{\"name\":\"x\"}"))
            .andExpect(status().isNotFound());
    }

    @Test
    void patch_blankName_422_blankEmail_keepsExisting() throws Exception {
        MockHttpSession admin = login("admin", "boot-pass-1");
        mvc.perform(patch("/api/admin/users/u1").session(admin).contentType(APPLICATION_JSON)
                .content("{\"name\":\"  \"}"))
            .andExpect(status().isUnprocessableEntity());
        jdbc.update("UPDATE app_user SET email = 'a@b.c' WHERE id = 'u1'");
        mvc.perform(patch("/api/admin/users/u1").session(admin).contentType(APPLICATION_JSON)
                .content("{\"email\":\"\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.email").value("a@b.c"));   // blank email은 미지정으로 정규화 — 기존 값 유지
    }

    @Test
    void resetPassword_oldFails_newWorks() throws Exception {
        MockHttpSession admin = login("admin", "boot-pass-1");
        mvc.perform(post("/api/admin/users/u1/reset-password").session(admin).contentType(APPLICATION_JSON)
                .content("{\"password\":\"new-pass-99\"}"))
            .andExpect(status().isNoContent());
        mvc.perform(post("/api/auth/login").contentType(APPLICATION_JSON)
                .content("{\"emp\":\"10001\",\"password\":\"pw-1234\"}"))
            .andExpect(status().isUnauthorized());
        login("10001", "new-pass-99");
    }

    @Test
    void resetPassword_invalidatesTargetExistingSession() throws Exception {
        MockHttpSession target = login("10001", "pw-1234");
        MockHttpSession admin = login("admin", "boot-pass-1");
        mvc.perform(get("/api/tree").session(target)).andExpect(status().isOk());
        mvc.perform(post("/api/admin/users/u1/reset-password").session(admin).contentType(APPLICATION_JSON)
                .content("{\"password\":\"new-pass-99\"}"))
            .andExpect(status().isNoContent());
        // 대상의 기존 세션은 다음 요청에서 즉시 401 (로그인 시점 salt ≠ 현재 DB salt)
        mvc.perform(get("/api/tree").session(target)).andExpect(status().isUnauthorized());
        // 리셋을 수행한 admin 본인 세션은 영향 없음
        mvc.perform(get("/api/admin/users").session(admin)).andExpect(status().isOk());
    }
}
