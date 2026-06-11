package com.worknote.auth;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

// AuthFilterTest와 properties 완전 동일 — 컨텍스트 캐시 + phase2mem DB 공유 (AdminBootstrap 멱등 보장)
@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:sqlite:file:phase2mem?mode=memory&cache=shared",
    "worknote.mode=server",
    "worknote.admin-password=boot-pass-1"
})
@AutoConfigureMockMvc
class AdminBootstrapTest {
    @Autowired UserMapper users;
    @Autowired MockMvc mvc;

    @Test
    void adminUserCreatedOnFirstBoot() {
        UserRow admin = users.findById("u-admin");
        assertThat(admin).isNotNull();
        assertThat(admin.emp()).isEqualTo("admin");
        assertThat(admin.roleId()).isEqualTo("admin");
        assertThat(admin.status()).isEqualTo("active");
        assertThat(users.findCredential("u-admin")).isNotNull();
    }

    @Test
    void adminCanLoginWithEnvPassword() throws Exception {
        mvc.perform(post("/api/auth/login").contentType(APPLICATION_JSON)
                .content("{\"emp\":\"admin\",\"password\":\"boot-pass-1\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.roleId").value("admin"));
    }
}
