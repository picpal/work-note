package com.worknote.pii;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import com.worknote.vault.NodeMapper;
import com.worknote.vault.NodeRow;
import com.worknote.vault.VaultNode;
import com.worknote.vault.VaultService;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Transactional
class TreePiiTest {
    @Autowired VaultService vault;
    @Autowired PiiService pii;
    @Autowired NodeMapper nodes;

    @Test void tree_carries_pii_for_flagged_note() {
        nodes.insert(new NodeRow("tp1", null, "note", "노트", 1, "010-1234-5678", "2026-06-14T00:00:00", null, null));
        pii.evaluate("tp1", "010-1234-5678");
        VaultNode n = vault.tree().stream().filter(v -> v.id().equals("tp1")).findFirst().orElseThrow();
        assertNotNull(n.pii());
        assertEquals("suspected", n.pii().status());
        assertTrue(n.pii().types().contains("phone"));
    }

    @Test void tree_pii_null_when_clean() {
        nodes.insert(new NodeRow("tp2", null, "note", "노트", 2, "clean", "2026-06-14T00:00:00", null, null));
        VaultNode n = vault.tree().stream().filter(v -> v.id().equals("tp2")).findFirst().orElseThrow();
        assertNull(n.pii());
    }
}
