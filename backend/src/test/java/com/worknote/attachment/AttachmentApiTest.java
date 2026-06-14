package com.worknote.attachment;

import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

/** 첨부 API 통합 (local 모드 = 무인증). 업로드/다운로드/삭제/공유 서빙 + nosniff·Content-Type. */
@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:sqlite:file::memory:?cache=shared",
    "worknote.upload.dir=build/test-attachments-api"
})
@AutoConfigureMockMvc
class AttachmentApiTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void seed() {
        jdbc.update("DELETE FROM attachment");
        jdbc.update("DELETE FROM share_link");
        jdbc.update("DELETE FROM node WHERE id = 'n1'");
        jdbc.update("INSERT INTO node(id,parent_id,type,name,position) VALUES('n1',NULL,'note','노트',1)");
    }

    private String upload(String name, byte[] body) throws Exception {
        return mvc.perform(multipart("/api/nodes/n1/attachments")
                .file(new MockMultipartFile("file", name, null, body)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.url").value(startsWith("/api/attachments/att-")))
            .andReturn().getResponse().getContentAsString();
    }

    @Test
    void upload_then_download_returnsBytesWithNosniff() throws Exception {
        String json = upload("a.png", new byte[]{1, 2, 3});
        String id = JsonPath.read(json, "$.id");
        mvc.perform(get("/api/attachments/" + id))
            .andExpect(status().isOk())
            .andExpect(header().string("X-Content-Type-Options", "nosniff"))
            .andExpect(header().string("Content-Type", "image/png"));
    }

    @Test
    void upload_disallowedExt_is422() throws Exception {
        mvc.perform(multipart("/api/nodes/n1/attachments")
                .file(new MockMultipartFile("file", "a.exe", null, new byte[]{1})))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.error").exists());
    }

    @Test
    void delete_then_download_is404() throws Exception {
        String json = upload("a.png", new byte[]{1});
        String id = JsonPath.read(json, "$.id");
        mvc.perform(delete("/api/attachments/" + id)).andExpect(status().isNoContent());
        mvc.perform(get("/api/attachments/" + id)).andExpect(status().isNotFound());
    }

    @Test
    void list_returnsNodeAttachmentsWithMeta() throws Exception {
        upload("a.png", new byte[]{1, 2, 3});
        upload("doc.pdf", new byte[]{4, 5});
        mvc.perform(get("/api/nodes/n1/attachments"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(2))
            .andExpect(jsonPath("$[?(@.filename=='a.png')].image")
                .value(org.hamcrest.Matchers.contains(true)))
            .andExpect(jsonPath("$[?(@.filename=='a.png')].mime")
                .value(org.hamcrest.Matchers.contains("image/png")))
            .andExpect(jsonPath("$[?(@.filename=='doc.pdf')].image")
                .value(org.hamcrest.Matchers.contains(false)))
            .andExpect(jsonPath("$[?(@.filename=='a.png')].url")
                .value(org.hamcrest.Matchers.contains(startsWith("/api/attachments/att-"))));
    }

    @Test
    void shareList_returnsAttachmentsWithShareScopedUrls() throws Exception {
        upload("a.png", new byte[]{9});
        String shareJson = mvc.perform(post("/api/nodes/n1/share").contentType("application/json").content("{}"))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String token = JsonPath.read(shareJson, "$.token");
        mvc.perform(get("/api/share/" + token + "/attachments"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].url")
                .value(startsWith("/api/share/" + token + "/attachments/att-")));
    }

    @Test
    void shareScoped_validToken_serves_and_otherNodeAttachment_404() throws Exception {
        String json = upload("a.png", new byte[]{9});
        String id = JsonPath.read(json, "$.id");
        // 공유 링크 생성 (local 모드 — 가드 통과)
        String shareJson = mvc.perform(post("/api/nodes/n1/share").contentType("application/json").content("{}"))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String token = JsonPath.read(shareJson, "$.token");
        mvc.perform(get("/api/share/" + token + "/attachments/" + id)).andExpect(status().isOk());
        // 다른 첨부(미존재)는 404
        mvc.perform(get("/api/share/" + token + "/attachments/att-nonexistent")).andExpect(status().isNotFound());
    }
}
