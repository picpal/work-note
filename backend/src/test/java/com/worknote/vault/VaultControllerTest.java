package com.worknote.vault;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = "spring.datasource.url=jdbc:sqlite:file::memory:?cache=shared")
@AutoConfigureMockMvc
class VaultControllerTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM tag");
        jdbc.update("DELETE FROM public_flag"); // 잔여 flag는 같은 id 재생성 시 create의 자동 exclude와 충돌
        jdbc.update("DELETE FROM node");
    }

    private void createFolder(String id, String parentId) throws Exception {
        String parent = parentId == null ? "null" : "\"" + parentId + "\"";
        mvc.perform(post("/api/nodes").contentType(APPLICATION_JSON)
                .content("{\"id\":\"" + id + "\",\"parentId\":" + parent
                    + ",\"type\":\"folder\",\"name\":\"" + id + "\"}"))
            .andExpect(status().isCreated());
    }

    private void createNote(String id, String parentId, String name) throws Exception {
        String parent = parentId == null ? "null" : "\"" + parentId + "\"";
        mvc.perform(post("/api/nodes").contentType(APPLICATION_JSON)
                .content("{\"id\":\"" + id + "\",\"parentId\":" + parent
                    + ",\"type\":\"note\",\"name\":\"" + name + "\",\"content\":\"body\"}"))
            .andExpect(status().isCreated());
    }

    @Test
    void healthOk() throws Exception {
        mvc.perform(get("/api/health"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("ok"));
    }

    @Test
    void createThenTreeRoundTrip() throws Exception {
        createFolder("f1", null);
        mvc.perform(post("/api/nodes").contentType(APPLICATION_JSON)
                .content("{\"id\":\"n1\",\"parentId\":\"f1\",\"type\":\"note\",\"name\":\"결제\",\"content\":\"body\"}"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.id").value("n1"))
            .andExpect(jsonPath("$.title").value("결제"));
        mvc.perform(get("/api/tree"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].id").value("f1"))
            .andExpect(jsonPath("$[0].children[0].title").value("결제"))
            .andExpect(jsonPath("$[0].children[0].name").doesNotExist());
    }

    @Test
    void patchUpdatesAndStamps() throws Exception {
        createNote("n1", null, "원래 제목");
        mvc.perform(patch("/api/nodes/n1").contentType(APPLICATION_JSON)
                .content("{\"name\":\"새 제목\",\"content\":\"abc\",\"tags\":[\"운영\"]}"))
            .andExpect(status().isNoContent())
            .andExpect(content().string(""));
        mvc.perform(get("/api/tree"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].title").value("새 제목"))
            .andExpect(jsonPath("$[0].tags[0]").value("운영"))
            .andExpect(jsonPath("$[0].updated").exists());
    }

    @Test
    void moveCycleReturns422() throws Exception {
        createFolder("f1", null);
        createFolder("f2", "f1");
        mvc.perform(post("/api/nodes/f1/move").contentType(APPLICATION_JSON)
                .content("{\"parentId\":\"f2\"}"))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.error").value(containsString("하위로 이동")));
    }

    @Test
    void moveReturns204WithEmptyBody() throws Exception {
        createFolder("f1", null);
        createNote("n1", null, "x");
        mvc.perform(post("/api/nodes/n1/move").contentType(APPLICATION_JSON)
                .content("{\"parentId\":\"f1\"}"))
            .andExpect(status().isNoContent())
            .andExpect(content().string(""));
    }

    @Test
    void deleteToTrashAndRestore() throws Exception {
        createFolder("f1", null);
        createNote("n1", "f1", "x");
        mvc.perform(delete("/api/nodes/f1")).andExpect(status().isNoContent());
        mvc.perform(get("/api/tree"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$").isEmpty());
        mvc.perform(get("/api/trash"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].id").value("f1"));
        mvc.perform(post("/api/trash/f1/restore"))
            .andExpect(status().isNoContent())
            .andExpect(content().string(""));
        mvc.perform(get("/api/tree"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$", hasSize(1)));
    }

    @Test
    void purgeOnlyFromTrash() throws Exception {
        createNote("n1", null, "x");
        mvc.perform(delete("/api/trash/n1"))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.error").exists());
        mvc.perform(delete("/api/nodes/n1")).andExpect(status().isNoContent());
        mvc.perform(delete("/api/trash/n1")).andExpect(status().isNoContent());
        mvc.perform(get("/api/trash"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$").isEmpty());
    }

    @Test
    void unknownIdIs404() throws Exception {
        mvc.perform(patch("/api/nodes/ghost").contentType(APPLICATION_JSON)
                .content("{\"name\":\"x\"}"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error").exists());
    }

    @Test
    void duplicateIdIs409() throws Exception {
        createNote("n1", null, "a");
        mvc.perform(post("/api/nodes").contentType(APPLICATION_JSON)
                .content("{\"id\":\"n1\",\"parentId\":null,\"type\":\"note\",\"name\":\"b\",\"content\":\"\"}"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.error").value(containsString("이미 존재")));
    }

    @Test
    void invalidBodyIs400() throws Exception {
        mvc.perform(post("/api/nodes").contentType(APPLICATION_JSON)
                .content("{\"type\":\"\",\"name\":\"\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").exists());
    }
}
