package com.worknote.vault;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.worknote.vault.dto.NodeLimits;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Collections;
import java.util.Map;

/**
 * 2026-08-07 보안감사 H-1 조치 — 저장 경로 입력 상한.
 * 무제한 본문은 그 자체로 DoS 입력이라 PATCH/POST 모두에서 막혀야 한다.
 */
@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:sqlite:file:nodelimitmem?mode=memory&cache=shared"
})
@AutoConfigureMockMvc
class NodeLimitsApiTest {
    @Autowired MockMvc mvc;
    @Autowired NodeMapper nodes;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;

    @BeforeEach
    void seed() {
        jdbc.update("DELETE FROM pii_flag WHERE node_id = 'lim1'");
        jdbc.update("DELETE FROM node WHERE id IN ('lim1','lim2')");
        nodes.insert(new NodeRow("lim1", null, "note", "n", 1, "", "2026-08-07T00:00:00", null, null));
    }

    private String body(Map<String, Object> m) throws Exception {
        return json.writeValueAsString(m);
    }

    @Test void patch_본문_상한초과_거부() throws Exception {
        String over = "a".repeat(NodeLimits.CONTENT_MAX + 1);
        mvc.perform(patch("/api/nodes/lim1").contentType("application/json")
                .content(body(Map.of("content", over))))
            .andExpect(status().isBadRequest());
    }

    @Test void patch_본문_상한이내_허용() throws Exception {
        String ok = "a".repeat(NodeLimits.CONTENT_MAX);
        mvc.perform(patch("/api/nodes/lim1").contentType("application/json")
                .content(body(Map.of("content", ok))))
            .andExpect(status().isOk());
    }

    @Test void patch_이름_상한초과_거부() throws Exception {
        mvc.perform(patch("/api/nodes/lim1").contentType("application/json")
                .content(body(Map.of("name", "n".repeat(NodeLimits.NAME_MAX + 1)))))
            .andExpect(status().isBadRequest());
    }

    @Test void patch_태그_개수초과_거부() throws Exception {
        mvc.perform(patch("/api/nodes/lim1").contentType("application/json")
                .content(body(Map.of("tags", Collections.nCopies(NodeLimits.TAGS_MAX + 1, "t")))))
            .andExpect(status().isBadRequest());
    }

    @Test void patch_태그_길이초과_거부() throws Exception {
        mvc.perform(patch("/api/nodes/lim1").contentType("application/json")
                .content(body(Map.of("tags", java.util.List.of("t".repeat(NodeLimits.TAG_MAX + 1))))))
            .andExpect(status().isBadRequest());
    }

    @Test void create_본문_상한초과_거부() throws Exception {
        mvc.perform(post("/api/nodes").contentType("application/json")
                .content(body(Map.of("id", "lim2", "type", "note", "name", "n",
                    "content", "a".repeat(NodeLimits.CONTENT_MAX + 1)))))
            .andExpect(status().isBadRequest());
    }

    @Test void create_이름_상한초과_거부() throws Exception {
        mvc.perform(post("/api/nodes").contentType("application/json")
                .content(body(Map.of("id", "lim2", "type", "note",
                    "name", "n".repeat(NodeLimits.NAME_MAX + 1)))))
            .andExpect(status().isBadRequest());
    }
}
