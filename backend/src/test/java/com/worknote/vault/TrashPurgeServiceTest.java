package com.worknote.vault;

import com.worknote.attachment.AttachmentService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:sqlite:file::memory:?cache=shared",
    "worknote.upload.dir=build/test-attachments-purge"
})
class TrashPurgeServiceTest {
    @Autowired TrashPurgeService purgeService;
    @Autowired VaultService vault;
    @Autowired NodeMapper nodes;
    @Autowired AttachmentService attachments;
    @Autowired Clock clock;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM attachment");
        jdbc.update("DELETE FROM tag");
        jdbc.update("DELETE FROM acl");
        jdbc.update("DELETE FROM public_flag");
        jdbc.update("DELETE FROM space");
        // node 전체 정리 — 형제 클래스가 과거 deleted_at을 남기면 purged 카운트 단언이 순서 의존 플레이크가 됨
        jdbc.update("DELETE FROM node");
        jdbc.update("DELETE FROM audit_log WHERE who = 'system'");
    }

    @AfterEach
    void cleanup() {
        // 공유 인메모리 DB — 잔여 행이 다른 테스트를 오염시키지 않게 정리
        jdbc.update("DELETE FROM attachment");
        jdbc.update("DELETE FROM node WHERE id LIKE 'pg-%'");
        jdbc.update("DELETE FROM audit_log WHERE who = 'system'");
    }

    private String daysAgo(int days) {
        return LocalDateTime.now(clock).minusDays(days)
            .format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
    }

    @Test
    void 보존기한_경과_휴지통_루트는_purge되고_감사가_남는다() {
        vault.create("pg-n1", null, "note", "만료 노트", "본문");
        vault.trash("pg-n1", "S2019-0007");
        jdbc.update("UPDATE node SET deleted_at = ? WHERE id = 'pg-n1'", daysAgo(31));

        int purged = purgeService.purgeExpired();

        assertThat(purged).isEqualTo(1);
        assertThat(nodes.findById("pg-n1")).isNull();
        Integer auditRows = jdbc.queryForObject(
            "SELECT COUNT(*) FROM audit_log WHERE who = 'system' AND act = 'node.purge' AND target LIKE 'pg-n1%'",
            Integer.class);
        assertThat(auditRows).isEqualTo(1);
    }

    @Test
    void 보존기한_이내_노드는_보존된다() {
        vault.create("pg-n2", null, "note", "최근 삭제 노트", "본문");
        vault.trash("pg-n2", "S2019-0007");
        jdbc.update("UPDATE node SET deleted_at = ? WHERE id = 'pg-n2'", daysAgo(29));

        int purged = purgeService.purgeExpired();

        assertThat(purged).isZero();
        assertThat(nodes.findById("pg-n2")).isNotNull();
    }

    @Test
    void purge시_노드의_첨부도_함께_정리된다() {
        vault.create("pg-a1", null, "note", "첨부 노트", "본문");
        attachments.store("pg-a1", "a.png", new byte[]{1}, "local");
        assertThat(attachments.findByNode("pg-a1")).hasSize(1);
        vault.trash("pg-a1", "S2019-0007");
        jdbc.update("UPDATE node SET deleted_at = ? WHERE id = 'pg-a1'", daysAgo(31));

        int purged = purgeService.purgeExpired();

        assertThat(purged).isEqualTo(1);
        assertThat(nodes.findById("pg-a1")).isNull();
        assertThat(attachments.findByNode("pg-a1")).isEmpty();
    }

    @Test
    void 폴더_서브트리는_루트만으로_통째_purge된다() {
        vault.create("pg-f1", null, "folder", "만료 폴더", null);
        vault.create("pg-c1", "pg-f1", "note", "자식 노트", "본문");
        vault.trash("pg-f1", "S2019-0007");
        jdbc.update("UPDATE node SET deleted_at = ? WHERE id IN ('pg-f1','pg-c1')", daysAgo(40));

        int purged = purgeService.purgeExpired();

        assertThat(purged).isEqualTo(1);   // 루트만 집계 (자식은 서브트리로 함께 삭제)
        assertThat(nodes.findById("pg-f1")).isNull();
        assertThat(nodes.findById("pg-c1")).isNull();
    }
}
