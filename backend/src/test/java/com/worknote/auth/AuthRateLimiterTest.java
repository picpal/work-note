package com.worknote.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AuthRateLimiterTest {

    /** 테스트용 가변 시계 — advance()로 시간 전진. */
    static class MutableClock extends Clock {
        Instant now = Instant.parse("2026-07-03T09:00:00Z");
        public ZoneId getZone() { return ZoneId.of("UTC"); }
        public Clock withZone(ZoneId zone) { return this; }
        public Instant instant() { return now; }
        void advance(Duration d) { now = now.plus(d); }
    }

    MutableClock clock;
    AuthRateLimiter limiter;

    @BeforeEach void setUp() {
        clock = new MutableClock();
        limiter = new AuthRateLimiter(clock);
    }

    @Test void underThreshold_notLocked() {
        for (int i = 0; i < 4; i++) limiter.recordFailure("login", "10001", "10.0.0.1");
        assertThat(limiter.isLocked("login", "10001", "10.0.0.1")).isFalse();
    }

    @Test void fifthFailure_locksAccount_andReportsTransition() {
        for (int i = 0; i < 4; i++) {
            assertThat(limiter.recordFailure("login", "10001", "10.0.0.1")).isFalse();
        }
        assertThat(limiter.recordFailure("login", "10001", "10.0.0.1")).isTrue();   // 전이
        assertThat(limiter.isLocked("login", "10001", "10.0.0.1")).isTrue();
        // 잠금 중 추가 실패는 전이 아님
        assertThat(limiter.recordFailure("login", "10001", "10.0.0.1")).isFalse();
    }

    @Test void lockExpires_andCounterResets() {
        for (int i = 0; i < 5; i++) limiter.recordFailure("login", "10001", "10.0.0.1");
        clock.advance(Duration.ofMinutes(5).plusSeconds(1));
        assertThat(limiter.isLocked("login", "10001", "10.0.0.1")).isFalse();
        // 만료 후 카운터 리셋 — 실패 1회로 재잠금되지 않음
        limiter.recordFailure("login", "10001", "10.0.0.1");
        assertThat(limiter.isLocked("login", "10001", "10.0.0.1")).isFalse();
    }

    @Test void successClearsAccountCounter() {
        for (int i = 0; i < 4; i++) limiter.recordFailure("login", "10001", "10.0.0.1");
        limiter.recordSuccess("login", "10001");
        for (int i = 0; i < 4; i++) limiter.recordFailure("login", "10001", "10.0.0.1");
        assertThat(limiter.isLocked("login", "10001", "10.0.0.1")).isFalse();
    }

    @Test void ipThreshold_locksAcrossAccounts() {
        // 같은 IP에서 계정을 바꿔가며 30회 실패 → IP 잠금 (스프레이 방어)
        for (int i = 0; i < 30; i++) limiter.recordFailure("login", "emp-" + i, "10.0.0.9");
        assertThat(limiter.isLocked("login", "fresh-emp", "10.0.0.9")).isTrue();
        // 다른 IP는 무관
        assertThat(limiter.isLocked("login", "fresh-emp", "10.0.0.10")).isFalse();
    }

    @Test void scopesAreIndependent() {
        for (int i = 0; i < 5; i++) limiter.recordFailure("login", "10001", "10.0.0.1");
        assertThat(limiter.isLocked("2fa", "10001", "10.0.0.1")).isFalse();
    }

    @Test void clearAll_resetsEverything() {
        for (int i = 0; i < 5; i++) limiter.recordFailure("login", "10001", "10.0.0.1");
        limiter.clearAll();
        assertThat(limiter.isLocked("login", "10001", "10.0.0.1")).isFalse();
    }
}
