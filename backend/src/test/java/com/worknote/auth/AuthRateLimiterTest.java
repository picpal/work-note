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

    /** 배리어로 동시에 출발시켜 서로 다른 키를 쏟아붓고, 관측 스레드로 그동안의 최대 항목 수를 잰다. */
    private int burst(int threads, int perThread, String keyPrefix) throws Exception {
        var peak = new java.util.concurrent.atomic.AtomicInteger();
        var observing = new java.util.concurrent.atomic.AtomicBoolean(true);
        var observer = new Thread(() -> {
            while (observing.get()) peak.accumulateAndGet(limiter.entryCount(), Math::max);
        });
        observer.start();

        var start = new java.util.concurrent.CyclicBarrier(threads);
        var done = new java.util.concurrent.CountDownLatch(threads);
        var failure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        var pool = java.util.concurrent.Executors.newFixedThreadPool(threads);
        try {
            for (int t = 0; t < threads; t++) {
                int tid = t;
                pool.execute(() -> {
                    try {
                        start.await();   // 동시 출발
                        for (int i = 0; i < perThread; i++) {
                            // 계정·IP 모두 매번 새 키 = 호출당 2항목 유입 (분산 스프레이 최악치)
                            limiter.recordFailure("login", keyPrefix + tid + "-" + i, tid + "." + i);
                        }
                    } catch (Throwable e) {
                        failure.compareAndSet(null, e);
                    } finally {
                        done.countDown();
                    }
                });
            }
            assertThat(done.await(120, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }
        observing.set(false);
        observer.join();
        assertThat(failure.get()).isNull();
        return peak.get();
    }

    /**
     * 동시 버스트에서도 상한이 '실제로' 지켜지는지 — 버스트가 끝난 뒤가 아니라 진행 '중'을 관측한다.
     * 끝난 뒤만 보면 안 되는 이유: 마지막 호출자가 정리하고 나가므로 사후 크기는 늘 얌전해 보인다.
     * 힙이 실제로 눌리는 건 버스트 도중이고, 상한은 그 시점에 성립해야 의미가 있다.
     * (락 없는 구현에서는 이 지점이 7배 넘게 초과했다 — 삽입이 축출보다 빠르면 상한이 성립하지 않는다.)
     */
    @Test void concurrentKeyBomb_boundHoldsDuringBurst() throws Exception {
        for (int round = 0; round < 3; round++) {
            limiter.clearAll();
            int peak = burst(32, 1_200, "ghost-" + round + "-");
            assertThat(peak)
                .as("round %d — 버스트 진행 중 관측된 최대 항목 수", round)
                .isLessThanOrEqualTo(AuthRateLimiter.MAX_ENTRIES);
            // 모든 호출자가 반환한 뒤 = 더 이상 정리 기회가 없는 시점
            assertThat(limiter.entryCount()).isLessThanOrEqualTo(AuthRateLimiter.MAX_ENTRIES);
        }
    }

    /** 동시 버스트 중에도 축출 정책은 그대로 — 잠긴 피해자 카운터는 살아남는다. */
    @Test void concurrentKeyBomb_lockedVictimSurvives() throws Exception {
        for (int i = 0; i < 5; i++) limiter.recordFailure("login", "victim", "10.0.0.1");
        assertThat(limiter.isLocked("login", "victim", "10.0.0.1")).isTrue();

        int peak = burst(16, 1_500, "zzz-");

        assertThat(limiter.isLocked("login", "victim", "10.0.0.2")).isTrue();   // 계정 키 잠금 유지
        assertThat(peak).isLessThanOrEqualTo(AuthRateLimiter.MAX_ENTRIES);
    }

    /**
     * 전부 잠금 상태여도 상한은 지켜진다 — 최후 수단 경로(미잠금이 하나도 없으면 잠금 항목도 축출).
     * 여기서도 순서는 LRU: 가장 오래된 잠금부터 밀리고 최근 잠금은 살아남는다.
     */
    @Test void allEntriesLocked_boundStillHolds() {
        int accounts = 12_000;
        for (int i = 0; i < accounts; i++) {
            for (int f = 0; f < 5; f++) limiter.recordFailure("login", "locked-" + i, "10.0.0.9");
        }
        assertThat(limiter.entryCount()).isLessThanOrEqualTo(AuthRateLimiter.MAX_ENTRIES);
        // 두 번째 인자 IP는 존재하지 않는 키 — 계정 키 단독 판정이 되게
        assertThat(limiter.isLocked("login", "locked-" + (accounts - 1), "10.0.0.1")).isTrue();
        assertThat(limiter.isLocked("login", "locked-0", "10.0.0.1")).isFalse();
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
