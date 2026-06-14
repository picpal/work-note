package com.worknote.pii;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import com.worknote.vault.NodeMapper;
import com.worknote.vault.NodeRow;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Transactional
class PiiServiceTest {
    @Autowired PiiService svc;
    @Autowired PiiMapper pii;
    @Autowired NodeMapper nodes;

    private void note(String id) {
        nodes.insert(new NodeRow(id, null, "note", "n", 1, "", "2026-06-14T00:00:00", null, null));
    }

    @Test void none_when_no_pii() {
        note("s1");
        PiiEval e = svc.evaluate("s1", "평범한 내용");
        assertEquals("none", e.status());
        assertNull(pii.findFlag("s1"));
    }

    @Test void suspected_on_detect_then_stays() {
        note("s2");
        assertEquals("suspected", svc.evaluate("s2", "010-1234-5678").status());
        assertEquals("suspected", svc.evaluate("s2", "010-1234-5678 추가").status());
        assertEquals("suspected", pii.findFlag("s2").status());
    }

    @Test void removed_pii_deletes_flag() {
        note("s3");
        svc.evaluate("s3", "010-1234-5678");
        assertEquals("none", svc.evaluate("s3", "내용 정리됨").status());
        assertNull(pii.findFlag("s3"));
    }

    @Test void exempted_stays_for_same_types_but_reverts_on_new_type() {
        note("s4");
        svc.evaluate("s4", "010-1234-5678");
        svc.requestException("s4", "e1", "오탐");
        svc.approve("s4", "admin");
        assertEquals("exempted", svc.evaluate("s4", "010-1234-5678 동일유형").status());
        assertEquals("suspected", svc.evaluate("s4", "010-1234-5678 a.b@c.com").status());
    }

    @Test void rejected_persists_through_save() {
        note("s5");
        svc.evaluate("s5", "010-1234-5678");
        svc.requestException("s5", "e1", null);
        svc.reject("s5", "admin", "실제 개인정보로 보임");
        assertEquals("rejected", svc.evaluate("s5", "010-1234-5678 여전히").status());
    }

    @Test void request_only_from_suspected_or_rejected() {
        note("s6");
        svc.evaluate("s6", "010-1234-5678");
        svc.requestException("s6", "e1", null);
        assertThrows(RuntimeException.class, () -> svc.requestException("s6", "e1", null));
    }
}
