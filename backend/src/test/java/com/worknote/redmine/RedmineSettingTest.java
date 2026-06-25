package com.worknote.redmine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.worknote.setting.SettingService;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:sqlite:file:memdb-redminesetting?mode=memory&cache=shared",
    "worknote.mode=server", "worknote.admin-password=x",
    "worknote.totp.key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
})
@AutoConfigureMockMvc
class RedmineSettingTest {

    @Autowired SettingService settings;
    @Autowired MockMvc mvc;

    // ─── Step 1: SettingService 단위 테스트 ───────────────────────────────

    @Test
    void defaults_then_roundtrip() {
        assertThat(settings.redmineEnabled()).isFalse();
        assertThat(settings.redmineBaseUrl()).isEmpty();
        settings.setRedmine(true, "http://redmine.intra");
        assertThat(settings.redmineEnabled()).isTrue();
        assertThat(settings.redmineBaseUrl()).isEqualTo("http://redmine.intra");
        // 원복 (공유 DB 오염 방지)
        settings.setRedmine(false, "");
    }

    // ─── Step 6: 관리자 GET/PUT 엔드포인트 테스트 ─────────────────────────

    private MockHttpSession adminSession() throws Exception {
        MockHttpSession session = new MockHttpSession();
        mvc.perform(post("/api/auth/login").session(session)
                .contentType(APPLICATION_JSON)
                .content("{\"emp\":\"admin\",\"password\":\"x\"}"))
            .andExpect(status().isOk());
        return session;
    }

    @Test
    void getRedmine_returnsDefaults() throws Exception {
        settings.setRedmine(false, ""); // 초기화
        mvc.perform(get("/api/admin/settings/redmine").session(adminSession()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.enabled").value(false))
            .andExpect(jsonPath("$.baseUrl").value(""));
    }

    @Test
    void putRedmine_persistsAndReturns() throws Exception {
        MockHttpSession s = adminSession();
        mvc.perform(put("/api/admin/settings/redmine").session(s)
                .contentType(APPLICATION_JSON)
                .content("{\"enabled\":true,\"baseUrl\":\"http://redmine.intra\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.enabled").value(true))
            .andExpect(jsonPath("$.baseUrl").value("http://redmine.intra"));
        mvc.perform(get("/api/admin/settings/redmine").session(s))
            .andExpect(jsonPath("$.enabled").value(true))
            .andExpect(jsonPath("$.baseUrl").value("http://redmine.intra"));
        // 원복
        settings.setRedmine(false, "");
    }
}
