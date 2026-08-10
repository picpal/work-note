package com.worknote.attachment;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 회수기의 <b>기본값이 꺼짐</b>임을 고정한다.
 *
 * <p>다른 가드는 전부 "무엇을 지울지"를 좁히지만, 업로드 루트가 이 앱 전용인지는 프로세스 안에서 증명할 수 없다 —
 * 우리 샤딩 모양은 MD5 기반 저장소 레이아웃과 겹친다. 그래서 켜는 행위 자체를 운영자의 단언으로 두었고,
 * 그 결정이 "합리적인 기본값을 주자"는 이유로 조용히 뒤집히면 파괴적 동작이 무설정으로 켜진다.
 *
 * <p>설정 파일 값과 주입된 빈을 함께 본다 — 둘 중 하나만 보면 {@code @Value} 기본값과 yml이 갈렸을 때 놓친다.
 *
 * <p>익명 file::memory:는 JVM 전역 단일 DB라 공통 노드 id를 심는 클래스끼리 PK가 충돌한다 — 클래스 전용 이름으로 격리
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:sqlite:file:reapdefaultmem?mode=memory&cache=shared")
class AttachmentReapDefaultOffTest {

    @Autowired Environment env;
    @Autowired AttachmentReapService reaper;

    @Test
    void 무설정_기동에서는_회수기가_꺼져_있다() {
        assertThat(env.getProperty("worknote.attachment.reap.grace-hours", Integer.class))
            .as("application.yml 기본값")
            .isZero();
        assertThat(reaper.enabled())
            .as("주입된 빈 — 켜려면 운영자가 루트 전용성을 단언해야 한다")
            .isFalse();
    }
}
