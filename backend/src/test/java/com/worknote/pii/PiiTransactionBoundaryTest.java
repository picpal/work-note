package com.worknote.pii;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 2026-08-07 보안감사 H-1 구조 가드.
 * 커넥션 풀이 1(SQLite 단일 라이터)이라 트랜잭션 안에서 본문을 스캔하면 요청 하나가 앱 전체를 멈춘다.
 * 경계가 되돌아가는 것을 컴파일 타임에 막을 방법이 없어 테스트로 고정한다.
 */
class PiiTransactionBoundaryTest {

    @Test void evaluate는_트랜잭션_밖() throws Exception {
        var m = PiiService.class.getMethod("evaluate", String.class, String.class);
        assertNull(m.getAnnotation(Transactional.class),
            "evaluate에 @Transactional을 붙이면 CPU 스캔이 유일한 DB 커넥션을 점유한다");
        assertNull(PiiService.class.getAnnotation(Transactional.class),
            "클래스 레벨 @Transactional도 같은 이유로 금지");
    }

    @Test void 플래그반영은_트랜잭션_안() throws Exception {
        var m = PiiFlagStore.class.getDeclaredMethod("apply", String.class, PiiDetector.Scan.class);
        assertNotNull(m.getAnnotation(Transactional.class),
            "read-then-write 원자성이 필요하다");
        assertNotSame(PiiService.class, PiiFlagStore.class,
            "같은 빈이면 자기호출이 프록시를 우회해 트랜잭션이 사라진다");
    }
}
