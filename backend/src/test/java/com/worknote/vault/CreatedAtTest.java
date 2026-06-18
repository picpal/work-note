package com.worknote.vault;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/** created_at: 폴더·노트 공통 노출(정렬용). insert는 NodeRow 무변경 + SQL에서 채움. */
@SpringBootTest
@Transactional
class CreatedAtTest {
    @Autowired VaultService vault;
    @Autowired NodeMapper nodes;

    /** 노트 created = updatedAt(=생성시각). insert SQL의 COALESCE(#{updatedAt}, now)로 채워진다. */
    @Test void tree_exposes_note_created_from_updated_at() {
        nodes.insert(new NodeRow("ca-note", null, "note", "n", 1, "body", "2026-06-14T08:30:00", null, null));
        VaultNode note = vault.tree().stream().filter(n -> "ca-note".equals(n.id())).findFirst().orElseThrow();
        assertEquals("2026-06-14T08:30:00", note.created());
    }

    /** 폴더는 updatedAt이 null이라 SQL이 현재 로컬시각으로 채움 → created는 항상 non-null. */
    @Test void tree_exposes_folder_created_non_null() {
        nodes.insert(new NodeRow("ca-folder", null, "folder", "F", 2, null, null, null, null));
        VaultNode folder = vault.tree().stream().filter(n -> "ca-folder".equals(n.id())).findFirst().orElseThrow();
        assertNotNull(folder.created());
        assertFalse(folder.created().isBlank());
    }

    /** 서비스 create()도 created를 즉시 반환(낙관적 응답) — 폴더·노트 모두. */
    @Test void create_returns_created() {
        VaultNode note = vault.create("ca-c1", null, "note", "제목", "본문");
        assertNotNull(note.created());
        VaultNode folder = vault.create("ca-c2", null, "folder", "폴더", null);
        assertNotNull(folder.created());
    }

    /** findCreated는 활성 노드만 — 휴지통 노드는 제외. */
    @Test void find_created_excludes_trashed() {
        nodes.insert(new NodeRow("ca-live", null, "note", "n", 1, "b", "2026-06-14T00:00:00", null, null));
        nodes.insert(new NodeRow("ca-dead", null, "note", "n", 2, "b", "2026-06-14T00:00:00", "2026-06-15T00:00:00", "local"));
        List<String> ids = nodes.findCreated().stream().map(CreatedRow::nodeId).toList();
        assertTrue(ids.contains("ca-live"));
        assertFalse(ids.contains("ca-dead"));
    }
}
