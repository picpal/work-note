package com.worknote.pii;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import com.worknote.vault.NodeMapper;
import com.worknote.vault.NodeRow;
import com.worknote.vault.VaultService;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Transactional
class UpdatedByTest {
    @Autowired VaultService vault;
    @Autowired NodeMapper nodes;

    @Test void update_sets_updated_by() {
        nodes.insert(new NodeRow("ub1", null, "note", "n", 1, "old", "2026-06-14T00:00:00", null, null));
        vault.update("ub1", null, "new content", List.of(), "e777");
        assertEquals("e777", nodes.findUpdatedBy("ub1"));
    }
}
