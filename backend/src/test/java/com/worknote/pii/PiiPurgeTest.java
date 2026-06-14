package com.worknote.pii;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import com.worknote.vault.NodeMapper;
import com.worknote.vault.NodeRow;
import com.worknote.vault.VaultService;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Transactional
class PiiPurgeTest {
    @Autowired VaultService vault;
    @Autowired PiiService pii;
    @Autowired PiiMapper piiMapper;
    @Autowired NodeMapper nodes;

    @Test void purge_removes_flag_and_notice() {
        nodes.insert(new NodeRow("pp1", null, "note", "n", 1, "010-1234-5678", "2026-06-14T00:00:00", "2026-06-14T00:00:00", "local"));
        pii.evaluate("pp1", "010-1234-5678");
        pii.notice("pp1", "local", "admin");
        vault.purge("pp1");
        assertNull(piiMapper.findFlag("pp1"));
        assertTrue(piiMapper.noticesFor("local").isEmpty());
    }
}
