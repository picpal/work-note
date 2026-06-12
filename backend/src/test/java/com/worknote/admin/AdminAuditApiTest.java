package com.worknote.admin;

import com.worknote.audit.AuditService;
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

import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:sqlite:file:phase2mem?mode=memory&cache=shared",
    "worknote.mode=server",
    "worknote.admin-password=boot-pass-1"
})
@AutoConfigureMockMvc
class AdminAuditApiTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired UserMapper users;
    @Autowired AuditService audit;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM audit_log");
        jdbc.update("DELETE FROM user_credential WHERE user_id <> 'u-admin'");
        jdbc.update("DELETE FROM app_user WHERE id <> 'u-admin'");
        users.insert(new UserRow("u1", "10001", null, "홍길동", "operator", "active", null));
        String salt = PasswordHasher.newSalt();
        users.insertCredential(new CredentialRow("u1", salt, PasswordHasher.hash("pw-1234", salt)));
        audit.logRaw("10001", "node.create", "n1", "127.0.0.1");
        jdbc.update("UPDATE audit_log SET at = '2026-06-01T10:00:00' WHERE target = 'n1'");
        audit.logRaw("10001", "node.trash", "n1", "127.0.0.1");
        jdbc.update("UPDATE audit_log SET at = '2026-06-05T10:00:00' WHERE act = 'node.trash'");
        audit.logRaw("20002", "node.create", "n2", "127.0.0.1");
        jdbc.update("UPDATE audit_log SET at = '2026-06-09T10:00:00' WHERE target = 'n2'");
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
    void nonAdmin_403() throws Exception {
        MockHttpSession op = login("10001", "pw-1234");
        mvc.perform(get("/api/admin/audit").session(op)).andExpect(status().isForbidden());
    }

    @Test
    void list_ordersByAtDesc_excludesLoginNoise() throws Exception {
        // 로그인 행(login.success)도 같이 조회되는 게 정상 — 시드 3건 + admin 로그인 1건
        mvc.perform(get("/api/admin/audit").session(admin()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.total").value(4))
            .andExpect(jsonPath("$.rows[1].target").value("n2"));
    }

    @Test
    void sameAt_tieBreaksByIdDesc() throws Exception {
        // 동일 at 2행 — 나중 insert(큰 id)가 먼저 와야 한다 (ORDER BY at DESC, id DESC)
        audit.logRaw("10001", "node.create", "tie1", "127.0.0.1");
        audit.logRaw("10001", "node.create", "tie2", "127.0.0.1");
        jdbc.update("UPDATE audit_log SET at = '2026-06-03T10:00:00' WHERE target IN ('tie1','tie2')");
        mvc.perform(get("/api/admin/audit").session(admin())
                .param("from", "2026-06-03T10:00:00").param("to", "2026-06-03T10:00:00"))
            .andExpect(jsonPath("$.total").value(2))
            .andExpect(jsonPath("$.rows[0].target").value("tie2"))
            .andExpect(jsonPath("$.rows[1].target").value("tie1"));
    }

    @Test
    void filter_byWho_andAct() throws Exception {
        mvc.perform(get("/api/admin/audit").session(admin()).param("who", "10001"))
            .andExpect(jsonPath("$.total").value(2));
        mvc.perform(get("/api/admin/audit").session(admin()).param("act", "node.create"))
            .andExpect(jsonPath("$.total").value(2));
        mvc.perform(get("/api/admin/audit").session(admin()).param("who", "10001").param("act", "node.create"))
            .andExpect(jsonPath("$.total").value(1));
    }

    @Test
    void blankParams_treatedAsAbsent() throws Exception {
        // UI가 ?who=&act= 형태로 보내도 필터 없음과 동일 — 빈 문자열 정확 일치로 0건 되는 지뢰 방지
        mvc.perform(get("/api/admin/audit").session(admin())
                .param("who", "").param("act", "").param("from", "").param("to", ""))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.total").value(4));
    }

    @Test
    void filter_byDateRange() throws Exception {
        mvc.perform(get("/api/admin/audit").session(admin())
                .param("from", "2026-06-02T00:00:00").param("to", "2026-06-06T00:00:00"))
            .andExpect(jsonPath("$.total").value(1))
            .andExpect(jsonPath("$.rows[0].act").value("node.trash"));
    }

    @Test
    void paging_limitAndOffset() throws Exception {
        // 로그인 1회만 수행해 행 수를 고정: login.success(최신) > n2 > node.trash > n1
        MockHttpSession admin = admin();
        mvc.perform(get("/api/admin/audit").session(admin).param("limit", "2").param("offset", "0"))
            .andExpect(jsonPath("$.total").value(4))
            .andExpect(jsonPath("$.rows.length()").value(2))
            .andExpect(jsonPath("$.rows[0].act").value("login.success"))
            .andExpect(jsonPath("$.rows[1].target").value("n2"));
        mvc.perform(get("/api/admin/audit").session(admin).param("limit", "2").param("offset", "2"))
            .andExpect(jsonPath("$.total").value(4))
            .andExpect(jsonPath("$.rows.length()").value(2))
            .andExpect(jsonPath("$.rows[0].act").value("node.trash"))
            .andExpect(jsonPath("$.rows[1].target").value("n1"));
        // limit 상한 200 클램프 — 500을 요청해도 에러 없이 동작
        mvc.perform(get("/api/admin/audit").session(admin).param("limit", "500"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.rows.length()").value(4));
    }
}
