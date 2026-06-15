package com.worknote.pii;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import com.worknote.vault.NodeMapper;
import com.worknote.vault.NodeRow;
import static org.junit.jupiter.api.Assertions.*;

// 전용 네임드 인메모리 DB — 익명 ::memory: 풀을 공유하는 비-@Transactional 커밋 테스트들이
// 같은 n1/n2 id를 남겨 PK 충돌을 내므로 격리(phase2mem/shareapimem과 동일 관례).
@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:sqlite:file:piisvcmem?mode=memory&cache=shared"
})
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

    @Test void exempted_stays_on_same_value_but_reverts_on_added_value() {
        note("s4");
        svc.evaluate("s4", "010-1234-5678");
        svc.requestException("s4", "e1", "오탐");
        svc.approve("s4", "admin");
        assertEquals("exempted", svc.evaluate("s4", "010-1234-5678 동일값").status());      // 같은 값 → 유지
        assertEquals("suspected", svc.evaluate("s4", "010-1234-5678 a.b@c.com").status()); // 값 추가 → 복귀
    }

    @Test void exempted_reverts_when_value_changes_same_type() {   // 1234→1235: 같은 phone 유형이어도 값 바뀌면 재감지
        note("sv");
        svc.evaluate("sv", "010-1234-1234");
        svc.requestException("sv", "e1", "샘플");
        svc.approve("sv", "admin");
        assertEquals("exempted", svc.evaluate("sv", "연락처 010-1234-1234 유지").status());
        assertEquals("suspected", svc.evaluate("sv", "연락처 010-1234-1235 변경").status());
    }

    @Test void exempted_reapplies_when_returning_to_approved_value() {   // 1112→1113→1112: 승인했던 값으로 돌아오면 다시 예외
        note("sr");
        svc.evaluate("sr", "010-1111-1112");
        svc.requestException("sr", "e1", "샘플");
        svc.approve("sr", "admin");
        assertEquals("suspected", svc.evaluate("sr", "010-1111-1113").status());   // 다른 값 → 의심
        assertEquals("exempted", svc.evaluate("sr", "010-1111-1112").status());     // 승인값 복귀 → 예외 재적용
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

    @Test void notice_dedup_then_ack() {
        note("n1");
        svc.evaluate("n1", "010-1234-5678");
        svc.notice("n1", "e9", "admin");
        svc.notice("n1", "e9", "admin");      // 중복 → 신규 미생성
        assertEquals(1, svc.noticesFor("e9").size());
        svc.ack("e9", null);
        assertTrue(svc.noticesFor("e9").isEmpty());
    }

    @Test void approve_creates_notice_to_requester() {
        note("n2");
        svc.evaluate("n2", "010-1234-5678");
        svc.requestException("n2", "eReq", "오탐");
        svc.approveWithNotice("n2", "admin");
        assertEquals(1, svc.noticesFor("eReq").size());
        assertEquals("approved", svc.noticesFor("eReq").get(0).get("kind"));
    }
}
