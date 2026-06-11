package com.worknote.auth;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:sqlite:file:phase2mem?mode=memory&cache=shared",
    "worknote.mode=server",
    "worknote.admin-password=boot-pass-1"
})
@AutoConfigureMockMvc
class AuthSignupTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM audit_log");
        jdbc.update("DELETE FROM user_credential WHERE user_id <> 'u-admin'");
        jdbc.update("DELETE FROM app_user WHERE id <> 'u-admin'");
    }

    private static String body(String emp) {
        return "{\"emp\":\"" + emp + "\",\"name\":\"신규자\",\"password\":\"pw-12345678\"}";
    }

    @Test
    void signup_createsPendingVisitor_withoutSession() throws Exception {
        mvc.perform(post("/api/auth/signup").contentType(APPLICATION_JSON).content(body("S2026-0142")))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.status").value("pending"));
        assertThat(jdbc.queryForObject(
            "SELECT role_id FROM app_user WHERE emp = 'S2026-0142'", String.class)).isEqualTo("visitor");
        assertThat(jdbc.queryForObject(
            "SELECT COUNT(*) FROM user_credential c JOIN app_user u ON u.id = c.user_id WHERE u.emp = 'S2026-0142'",
            Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
            "SELECT COUNT(*) FROM audit_log WHERE act = 'signup' AND who = 'S2026-0142'", Integer.class)).isEqualTo(1);
    }

    @Test
    void signup_duplicateEmp_409() throws Exception {
        mvc.perform(post("/api/auth/signup").contentType(APPLICATION_JSON).content(body("S2026-0142")))
            .andExpect(status().isCreated());
        mvc.perform(post("/api/auth/signup").contentType(APPLICATION_JSON).content(body("S2026-0142")))
            .andExpect(status().isConflict());
    }

    @Test
    void pendingUser_cannotLogin() throws Exception {
        mvc.perform(post("/api/auth/signup").contentType(APPLICATION_JSON).content(body("S2026-0142")))
            .andExpect(status().isCreated());
        mvc.perform(post("/api/auth/login").contentType(APPLICATION_JSON)
                .content("{\"emp\":\"S2026-0142\",\"password\":\"pw-12345678\"}"))
            .andExpect(status().isForbidden());
    }

    @Test
    void signup_shortPassword_400() throws Exception {
        mvc.perform(post("/api/auth/signup").contentType(APPLICATION_JSON)
                .content("{\"emp\":\"S1\",\"name\":\"n\",\"password\":\"short\"}"))
            .andExpect(status().isBadRequest());
    }
}
