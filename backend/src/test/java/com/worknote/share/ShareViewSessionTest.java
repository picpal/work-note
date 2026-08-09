package com.worknote.share;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 소진된 링크의 첨부 자격(표식) 단위 테스트 — 자격은 (링크 × 주체)에 묶이고 TTL로 끝난다.
 * 개수 상한이 없다는 것도 자격의 성질이라 여기서 못 박는다(정당한 열람자가 링크를 많이
 * 열었다는 이유로 조용히 접근을 잃으면 안 된다).
 */
class ShareViewSessionTest {

    /** 수동으로 흐르는 시계 — TTL 경계를 실시간 대기 없이 검증한다. */
    private static final class TickClock extends Clock {
        private Instant now = Instant.parse("2026-06-12T10:00:00Z");

        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }

        void advance(Duration d) { now = now.plus(d); }
    }

    private final TickClock clock = new TickClock();
    private final ShareViewSession session = new ShareViewSession(clock);

    /** 새 브라우저 세션 진입 — 이후 호출은 이 요청 컨텍스트에서 일어난다. */
    private static MockHttpServletRequest newSession() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(req));
        return req;
    }

    @AfterEach
    void resetContext() {
        RequestContextHolder.resetRequestAttributes();
    }

    // 1. 표식은 링크 하나 × 주체 하나에만 유효하다
    @Test
    void 표식은_링크와_주체_양쪽에_묶인다() {
        newSession();
        session.markViewed("link-1", "10001");

        assertThat(session.hasViewed("link-1", "10001")).isTrue();
        assertThat(session.hasViewed("link-2", "10001")).isFalse();   // 열지 않은 링크
        assertThat(session.hasViewed("link-1", "20002")).isFalse();   // 열지 않은 계정
    }

    // 2. ★핵심★ 같은 세션에서 계정이 바뀌면(공용 PC 교대 로그인) 이전 계정의 자격은 전부 사라진다.
    //    changeSessionId()가 세션 내용을 유지하므로 표식이 잔류하면 새 계정이 물려받는다.
    @Test
    void 주체가_바뀌면_이전_주체의_표식은_전부_무효화된다() {
        newSession();
        session.markViewed("link-1", "10001");
        session.markViewed("link-2", "10001");

        assertThat(session.hasViewed("link-1", "20002")).isFalse();

        // 되돌아와도 부활하지 않는다 — 주체 전환 시점에 폐기(fail-closed)
        assertThat(session.hasViewed("link-1", "10001")).isFalse();
        assertThat(session.hasViewed("link-2", "10001")).isFalse();
    }

    // 3. 개수 상한 없음 — 링크를 많이 열었다고 가장 오래된 자격을 잃지 않는다
    @Test
    void 표식은_개수로_축출되지_않는다() {
        newSession();
        session.markViewed("link-first", "10001");
        for (int i = 0; i < 200; i++) {
            session.markViewed("link-" + i, "10001");
        }

        assertThat(session.hasViewed("link-first", "10001")).isTrue();
        assertThat(session.hasViewed("link-0", "10001")).isTrue();
        assertThat(session.hasViewed("link-199", "10001")).isTrue();
    }

    // 4. 자격의 끝은 개수가 아니라 시간이다
    @Test
    void 표식은_TTL이_지나면_만료된다() {
        newSession();
        session.markViewed("link-1", "10001");

        clock.advance(ShareViewSession.TTL.minusSeconds(1));
        assertThat(session.hasViewed("link-1", "10001")).isTrue();

        clock.advance(Duration.ofSeconds(1));
        assertThat(session.hasViewed("link-1", "10001")).isFalse();
    }

    // 5. 재열람은 TTL을 갱신한다 — 열람수를 새로 태운 자격이므로 새로 시작한다
    @Test
    void 재열람하면_TTL이_다시_시작된다() {
        newSession();
        session.markViewed("link-1", "10001");
        clock.advance(ShareViewSession.TTL.minusSeconds(1));
        session.markViewed("link-1", "10001");

        clock.advance(ShareViewSession.TTL.minusSeconds(1));
        assertThat(session.hasViewed("link-1", "10001")).isTrue();
    }

    // 6. local 모드 — 주체가 null이어도 자기 자신과는 일치한다(무인증이 곧 단일 주체)
    @Test
    void local모드_주체_null도_하나의_주체로_취급된다() {
        newSession();
        session.markViewed("link-1", null);

        assertThat(session.hasViewed("link-1", null)).isTrue();
        assertThat(session.hasViewed("link-1", "10001")).isFalse();
    }

    // 7. 다른 브라우저 세션에는 표식이 없다
    @Test
    void 표식은_그_세션에만_남는다() {
        newSession();
        session.markViewed("link-1", "10001");

        newSession();
        assertThat(session.hasViewed("link-1", "10001")).isFalse();
    }

    // 8. 첨부 요청만으로는 세션을 만들지 않는다(조회는 세션 생성 없음)
    @Test
    void 조회는_세션을_새로_만들지_않는다() {
        MockHttpServletRequest req = newSession();

        assertThat(session.hasViewed("link-1", "10001")).isFalse();
        assertThat(req.getSession(false)).isNull();
    }

    // 9. 요청 밖(스케줄러·단위 호출)에서는 조용히 무시한다
    @Test
    void 요청_컨텍스트_밖에서는_조용히_무시한다() {
        RequestContextHolder.resetRequestAttributes();

        session.markViewed("link-1", "10001");
        assertThat(session.hasViewed("link-1", "10001")).isFalse();
    }
}
