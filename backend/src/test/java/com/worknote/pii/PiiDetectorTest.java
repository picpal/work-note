package com.worknote.pii;

import org.junit.jupiter.api.Test;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class PiiDetectorTest {

    @Test void 유효_주민번호_탐지() {
        assertTrue(PiiDetector.detect("주민번호 900101-1234568 입니다").contains(PiiType.RRN));
    }

    @Test void 체크섬_무효라도_주민형식이면_탐지() {   // 경고용 — 형식만 맞으면 양성(가짜/테스트 번호 포함)
        assertTrue(PiiDetector.detect("900101-1234561").contains(PiiType.RRN));
        assertTrue(PiiDetector.detect("9001011234561").contains(PiiType.RRN));   // 구분자 없어도
    }

    @Test void 주민_성별자리_범위밖은_음성() {   // 형식 자체가 아니면 음성(7번째 자리 1-8만)
        assertFalse(PiiDetector.detect("900101-9234567").contains(PiiType.RRN));
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

    @Test void 사업자번호_형식이면_탐지() {   // 체크섬 무효라도 NNN-NN-NNNNN 형식이면 양성
        assertTrue(PiiDetector.detect("220-81-62517").contains(PiiType.BIZ));
        assertTrue(PiiDetector.detect("123-45-67890").contains(PiiType.BIZ));
        assertFalse(PiiDetector.detect("12345-67890").contains(PiiType.BIZ));   // 대시 구조 다르면 음성
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

    @Test void scanMatches_위치_포함() {
        var ms = PiiDetector.scanMatches("연락처 010-1234-5678");
        assertEquals(1, ms.size());
        assertEquals(PiiType.PHONE, ms.get(0).type());
        assertEquals("010-1234-5678", ms.get(0).value());
        assertEquals(4, ms.get(0).start());   // "연락처 "=4자(공백 포함)
    }

    @Test void scanMatches_다중_등장순서() {
        var ms = PiiDetector.scanMatches("a@b.com 그리고 010-1234-5678");
        assertEquals(2, ms.size());
        assertEquals(PiiType.EMAIL, ms.get(0).type());   // start 오름차순
        assertEquals(PiiType.PHONE, ms.get(1).type());
        assertTrue(ms.get(0).start() < ms.get(1).start());
    }

    @Test void scanMatches_빈텍스트() {
        assertTrue(PiiDetector.scanMatches("").isEmpty());
        assertTrue(PiiDetector.scanMatches(null).isEmpty());
    }

    @Test void 개행_가로지르면_미탐지() {   // [- \t]로 좁혀 \n은 구분자 아님 — 라인 포커스 정확성 보장
        assertFalse(PiiDetector.detect("010\n1234\n5678").contains(PiiType.PHONE));
        assertEquals(1, PiiDetector.scanMatches("전화 010-1234-5678").size());   // 한 줄 정상 표기는 그대로 탐지
    }
}
