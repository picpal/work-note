package com.worknote.pii;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import com.worknote.auth.UserMapper;
import com.worknote.auth.UserRow;
import com.worknote.vault.NodeMapper;
import com.worknote.vault.NodeRow;
import com.worknote.vault.VaultNode;
import com.worknote.vault.VaultService;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Transactional
class UpdatedByTest {
    @Autowired VaultService vault;
    @Autowired NodeMapper nodes;
    @Autowired UserMapper users;

    @Test void update_sets_updated_by() {
        nodes.insert(new NodeRow("ub1", null, "note", "n", 1, "old", "2026-06-14T00:00:00", null, null));
        vault.update("ub1", null, "new content", List.of(), "e777");
        assertEquals("e777", nodes.findUpdatedBy("ub1"));
    }

    /** 트리는 수정자를 "사번(이름)" 라벨로 노출 — updated_by(사번)→app_user 조인. */
    @Test void tree_exposes_updater_label() {
        users.insert(new UserRow("u-ub", "e778", null, "홍길동", "operator", "active", null));
        nodes.insert(new NodeRow("ub2", null, "note", "n", 1, "old", "2026-06-14T00:00:00", null, null));
        vault.update("ub2", null, "new content", List.of(), "e778");
        VaultNode note = vault.tree().stream().filter(n -> "ub2".equals(n.id())).findFirst().orElseThrow();
        assertEquals("e778(홍길동)", note.updatedBy());
    }

    /** 매칭되는 사용자가 없으면(local 모드 등) 수정자 라벨은 null — 프런트는 수정일만 표시. */
    @Test void tree_updater_null_when_no_matching_user() {
        nodes.insert(new NodeRow("ub3", null, "note", "n", 1, "old", "2026-06-14T00:00:00", null, null));
        vault.update("ub3", null, "x", List.of(), "local");
        VaultNode note = vault.tree().stream().filter(n -> "ub3".equals(n.id())).findFirst().orElseThrow();
        assertNull(note.updatedBy());
    }
}
