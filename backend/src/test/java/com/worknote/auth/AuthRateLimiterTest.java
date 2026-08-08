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
        for (int i = 0; i < 29; i++) {
            assertThat(limiter.recordFailure("login", "emp-" + i, "10.0.0.9")).isFalse();
        }
        assertThat(limiter.recordFailure("login", "emp-29", "10.0.0.9")).isTrue();   // 30번째 = IP 잠금 전이
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

    /** 키 폭탄 — 전 항목이 '방금 touch'라 나이 기반 스윕이 아무것도 못 지우는 최악 케이스. */
    @Test void keyBomb_withAllEntriesFresh_staysWithinMaxEntries() {
        int bomb = AuthRateLimiter.MAX_ENTRIES + 2_000;
        for (int i = 0; i < bomb; i++) {
            limiter.recordFailure("login", "ghost-" + i, "10.0.0.9");   // 시계 전진 없음 = 전부 활성
        }
        assertThat(limiter.entryCount()).isLessThanOrEqualTo(AuthRateLimiter.MAX_ENTRIES);
    }

    /** 축출은 미잠금 항목을 먼저 소비 — 키 폭탄으로 남의 잠금을 밀어내지 못한다. */
    @Test void eviction_prefersUnlockedEntries_lockedVictimSurvives() {
        for (int i = 0; i < 5; i++) limiter.recordFailure("login", "victim", "10.0.0.1");
        assertThat(limiter.isLocked("login", "victim", "10.0.0.1")).isTrue();

        int bomb = AuthRateLimiter.MAX_ENTRIES + 2_000;
        for (int i = 0; i < bomb; i++) limiter.recordFailure("login", "ghost-" + i, "10.0.0.9");

        assertThat(limiter.isLocked("login", "victim", "10.0.0.2")).isTrue();   // 계정 키 잠금 유지
    }

    /**
     * 축출 순서는 touch 순번(LRU) — 계정명으로 조작 불가.
     * 'zzz-' 도배는 피해자보다 사전순으로 뒤라, 키 문자열로 동률을 깨면 피해자 카운터가 먼저 날아간다.
     */
    @Test void eviction_doesNotDropActivelyCountingVictim_regardlessOfKeyOrder() {
        for (int i = 0; i < 6_000; i++) limiter.recordFailure("login", "zzz-a-" + i, "10.0.0.9");
        for (int i = 0; i < 4; i++) limiter.recordFailure("login", "10001", "10.0.0.1");   // 최근 touch
        for (int i = 0; i < 6_000; i++) limiter.recordFailure("login", "zzz-b-" + i, "10.0.0.9");

        // 카운터가 유지됐다면 5번째 실패에서 잠금 전이
        assertThat(limiter.recordFailure("login", "10001", "10.0.0.1")).isTrue();
        assertThat(limiter.entryCount()).isLessThanOrEqualTo(AuthRateLimiter.MAX_ENTRIES);
    }

    /** 축출이 핵심 계약(5회 잠금 / LOCK_DURATION 후 해제)을 깨지 않는지. */
    @Test void coreLockContract_survivesEviction() {
        int bomb = AuthRateLimiter.MAX_ENTRIES + 2_000;
        for (int i = 0; i < bomb; i++) limiter.recordFailure("login", "ghost-" + i, "10.0.0.9");

        for (int i = 0; i < 4; i++) {
            assertThat(limiter.recordFailure("login", "10001", "10.0.0.1")).isFalse();
        }
        assertThat(limiter.recordFailure("login", "10001", "10.0.0.1")).isTrue();
        assertThat(limiter.isLocked("login", "10001", "10.0.0.1")).isTrue();

        clock.advance(AuthRateLimiter.LOCK_DURATION.plusSeconds(1));
        assertThat(limiter.isLocked("login", "10001", "10.0.0.1")).isFalse();
        limiter.recordFailure("login", "10001", "10.0.0.1");
        assertThat(limiter.isLocked("login", "10001", "10.0.0.1")).isFalse();   // 카운터 리셋됨
    }
}
