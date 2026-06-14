package com.worknote.pii;

import org.junit.jupiter.api.Test;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class PiiDetectorTest {

    @Test void 유효_주민번호_탐지() {
        assertTrue(PiiDetector.detect("주민번호 900101-1234568 입니다").contains(PiiType.RRN));
    }

    @Test void 체크섬_무효_주민형식은_음성() {
        assertFalse(PiiDetector.detect("900101-1234561").contains(PiiType.RRN));
    }

    @Test void 휴대폰_탐지() {
        assertTrue(PiiDetector.detect("연락처 010-1234-5678").contains(PiiType.PHONE));
        assertTrue(PiiDetector.detect("01098765432").contains(PiiType.PHONE));
    }

    @Test void 이메일_탐지() {
        assertTrue(PiiDetector.detect("a.b+c@example.co.kr 로 회신").contains(PiiType.EMAIL));
    }

    @Test void 신용카드_Luhn_통과만_탐지() {
        assertTrue(PiiDetector.detect("4111-1111-1111-1111").contains(PiiType.CARD));
        assertFalse(PiiDetector.detect("4111-1111-1111-1112").contains(PiiType.CARD));
    }

    @Test void 사업자번호_체크섬() {
        assertTrue(PiiDetector.detect("220-81-62517").contains(PiiType.BIZ));
        assertFalse(PiiDetector.detect("123-45-67890").contains(PiiType.BIZ));
    }

    @Test void 여권_운전면허() {
        assertTrue(PiiDetector.detect("여권 M12345678").contains(PiiType.PASSPORT));
        assertTrue(PiiDetector.detect("면허 11-22-123456-78").contains(PiiType.DRIVER));
    }

    @Test void PII_없으면_빈집합() {
        assertTrue(PiiDetector.detect("그냥 평범한 회의록 내용입니다.").isEmpty());
        assertTrue(PiiDetector.detect(null).isEmpty());
    }

    @Test void CSV_직렬화_정렬() {
        assertEquals("email,phone", PiiType.csv(Set.of(PiiType.PHONE, PiiType.EMAIL)));
    }
}
