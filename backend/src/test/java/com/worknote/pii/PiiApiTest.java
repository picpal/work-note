package com.worknote.pii;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.worknote.vault.NodeMapper;
import com.worknote.vault.NodeRow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/** PATCH 저장 시 PII 재탐지 → pii 응답 (local 모드 = 무인증). content 변경 시에만 pii 키. */
@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:sqlite:file::memory:?cache=shared"
})
@AutoConfigureMockMvc
class PiiApiTest {
    @Autowired MockMvc mvc;
    @Autowired NodeMapper nodes;
    @Autowired JdbcTemplate jdbc;
    @Autowired PiiService pii;

    @BeforeEach
    void seed() {
        // 공유 인메모리 DB라 JVM 수명 동안 누적 — 본 테스트 노드/플래그/알림만 정리
        jdbc.update("DELETE FROM pii_notice WHERE node_id IN ('pc1','pc2')");
        jdbc.update("DELETE FROM pii_flag WHERE node_id IN ('pa1','pa2','pc1','pc2')");
        jdbc.update("DELETE FROM node WHERE id IN ('pa1','pa2','pc1','pc2')");
    }

    @Test void patch_returns_pii_when_detected() throws Exception {
        nodes.insert(new NodeRow("pa1", null, "note", "n", 1, "", "2026-06-14T00:00:00", null, null));
        mvc.perform(patch("/api/nodes/pa1").contentType("application/json")
                .content("{\"content\":\"내 번호 010-1234-5678\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.pii.status").value("suspected"))
            .andExpect(jsonPath("$.pii.types[0]").value("phone"));
    }

    @Test void patch_tags_only_no_pii_key() throws Exception {
        nodes.insert(new NodeRow("pa2", null, "note", "n", 2, "x", "2026-06-14T00:00:00", null, null));
        mvc.perform(patch("/api/nodes/pa2").contentType("application/json")
                .content("{\"tags\":[\"a\"]}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.pii").doesNotExist());
    }

    @Test void exception_request_then_admin_list_and_approve() throws Exception {
        nodes.insert(new NodeRow("pc1", null, "note", "노트제목", 5, "", "2026-06-14T00:00:00", null, null));
        pii.evaluate("pc1", "010-1234-5678");

        mvc.perform(post("/api/nodes/pc1/pii/exception").contentType("application/json").content("{\"reason\":\"오탐\"}"))
            .andExpect(status().isNoContent());

        mvc.perform(get("/api/admin/pii/requests"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].nodeId").value("pc1"))
            .andExpect(jsonPath("$[0].title").value("노트제목"));

        mvc.perform(post("/api/admin/pii/notes/pc1/approve"))
            .andExpect(status().isNoContent());
    }

    @Test void me_notices_and_ack() throws Exception {
        nodes.insert(new NodeRow("pc2", null, "note", "노트2", 6, "", "2026-06-14T00:00:00", null, null));
        pii.evaluate("pc2", "010-1234-5678");
        pii.notice("pc2", "local", "admin");
        mvc.perform(get("/api/me/pii-notices"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].noteTitle").value("노트2"));
        mvc.perform(post("/api/me/pii-notices/ack").contentType("application/json").content("{}"))
            .andExpect(status().isNoContent());
    }
}
