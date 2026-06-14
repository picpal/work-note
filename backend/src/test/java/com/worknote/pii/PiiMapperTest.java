package com.worknote.pii;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import com.worknote.vault.NodeMapper;
import com.worknote.vault.NodeRow;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Transactional
class PiiMapperTest {
    @Autowired PiiMapper pii;
    @Autowired NodeMapper nodes;

    @Test void flag_upsert_and_find() {
        nodes.insert(new NodeRow("pm1", null, "note", "n", 1, "c", "2026-06-14T00:00:00", null, null));
        pii.insertFlag(new PiiFlagRow("pm1", "suspected", "rrn", "2026-06-14T00:00:00", null, null, null, null, null, null));
        PiiFlagRow row = pii.findFlag("pm1");
        assertEquals("suspected", row.status());
        assertEquals("rrn", row.types());
        assertEquals(1, pii.activeFlags().size());
        pii.deleteFlag("pm1");
        assertNull(pii.findFlag("pm1"));
    }

    @Test void notice_dedup_and_ack() {
        nodes.insert(new NodeRow("pm2", null, "note", "제목", 1, "c", "2026-06-14T00:00:00", null, null));
        pii.insertNotice(new PiiNoticeRow(null, "pm2", "e100", "flagged", null, "admin", "2026-06-14T00:00:00", null));
        Long dup = pii.findUnackedNoticeId("pm2", "e100", "flagged");
        assertNotNull(dup);
        List<java.util.Map<String, Object>> mine = pii.noticesFor("e100");
        assertEquals(1, mine.size());
        assertEquals("제목", mine.get(0).get("noteTitle"));
        pii.ack("e100", null);
        assertTrue(pii.noticesFor("e100").isEmpty());
    }
}
