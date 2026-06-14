package com.worknote.attachment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.worknote.vault.VaultException;
import java.nio.file.Files;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:sqlite:file::memory:?cache=shared",
    "worknote.upload.dir=build/test-attachments"
})
class AttachmentServiceTest {
    @Autowired AttachmentService svc;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void seedNode() {
        jdbc.update("DELETE FROM attachment");
        jdbc.update("DELETE FROM node WHERE id = 'n1'");
        jdbc.update("INSERT INTO node(id,parent_id,type,name,position) VALUES('n1',NULL,'note','노트',1)");
    }

    @Test
    void store_writesFileAndRow() throws Exception {
        AttachmentRow r = svc.store("n1", "a.png", new byte[]{1, 2, 3}, "local");
        assertThat(r.id()).startsWith("att-");
        assertThat(r.nodeId()).isEqualTo("n1");
        assertThat(r.size()).isEqualTo(3);
        assertThat(Files.exists(svc.pathOf(r))).isTrue();
        assertThat(svc.findById(r.id())).isNotNull();
    }

    @Test
    void store_rejectsDisallowedExt() {
        assertThatThrownBy(() -> svc.store("n1", "a.exe", new byte[]{1}, "local"))
            .isInstanceOf(VaultException.class);
    }

    @Test
    void delete_removesFileAndRow() {
        AttachmentRow r = svc.store("n1", "a.png", new byte[]{1}, "local");
        svc.delete(r.id());
        assertThat(svc.findById(r.id())).isNull();
        assertThat(Files.exists(svc.pathOf(r))).isFalse();
    }

    @Test
    void deleteForNodes_removesAll() {
        svc.store("n1", "a.png", new byte[]{1}, "local");
        svc.store("n1", "b.png", new byte[]{2}, "local");
        svc.deleteForNodes(List.of("n1"));
        assertThat(svc.findByNode("n1")).isEmpty();
    }
}
