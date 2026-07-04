package com.worknote.auth;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * 인증 시도 제한 (CWE-307). 계정·IP 키별 연속 실패 카운터 — 임계 초과 시 일시 잠금.
 * 인메모리 단일 인스턴스 전제(폐쇄망 소규모, 재기동 시 초기화 수용).
 * 계정 키는 엄격(5회), IP 키는 프록시/NAT 오탐을 줄이기 위해 느슨(30회 — 계정 스프레이 방어).
 */
@Component
public class AuthRateLimiter {

    public static final int ACCOUNT_MAX_FAILS = 5;
    public static final int IP_MAX_FAILS = 30;
    public static final Duration LOCK_DURATION = Duration.ofMinutes(5);
    /** 메모리 상한 — 초과 시 만료 항목 정리. 키 폭탄(무작위 계정명 대량 시도)로 인한 힙 고갈 방지. */
    private static final int MAX_ENTRIES = 10_000;

    private record Entry(int fails, Instant lockedUntil, Instant touchedAt) {}

    private final Clock clock;
    private final Map<String, Entry> entries = new ConcurrentHashMap<>();

    public AuthRateLimiter(Clock clock) {
        this.clock = clock;
    }

    /** 잠금 여부 — 계정 키 또는 IP 키 중 하나라도 잠겨 있으면 true. */
    public boolean isLocked(String scope, String accountKey, String ip) {
        return lockedNow(key(scope, "acct", accountKey)) || lockedNow(key(scope, "ip", ip));
    }

    /** 실패 기록 — 계정·IP 카운터 동시 증가. 이번 실패로 잠금이 '시작'되면 true(감사 로그 트리거용). */
    public boolean recordFailure(String scope, String accountKey, String ip) {
        sweepIfOverflow();
        boolean acctTransition = bump(key(scope, "acct", accountKey), ACCOUNT_MAX_FAILS);
        boolean ipTransition = bump(key(scope, "ip", ip), IP_MAX_FAILS);
        return acctTransition || ipTransition;
    }

    /** 성공 시 계정 카운터 해제. IP 카운터는 유지 — 성공 1회로 스프레이 카운터가 씻기지 않게. */
    public void recordSuccess(String scope, String accountKey) {
        entries.remove(key(scope, "acct", accountKey));
    }

    /** 전체 초기화 — 통합 테스트 격리용. */
    public void clearAll() {
        entries.clear();
    }

    private boolean lockedNow(String key) {
        Entry e = entries.get(key);
        if (e == null || e.lockedUntil() == null) return false;
        if (!Instant.now(clock).isBefore(e.lockedUntil())) {
            entries.remove(key);   // 만료 — lazy 정리 + 카운터 리셋. bump의 compute와 remove가 경합해도
                                   // 어느 쪽이든 '만료 항목 제거 후 새로 카운트'로 수렴해 무해 (원자성 불요)
            return false;
        }
        return true;
    }

    /** @return 이번 실패로 잠금이 '시작'됐으면 true. 이미 잠금 중이면 상태 유지하고 false. */
    private boolean bump(String key, int maxFails) {
        Instant now = Instant.now(clock);
        boolean[] transition = {false};   // compute 람다 안에서만 세팅 — 잠금 중 재호출과 구분
        entries.compute(key, (k, e) -> {
            if (e != null && e.lockedUntil() != null) {
                if (now.isBefore(e.lockedUntil())) return e;              // 잠금 중 — 변화 없음
                e = null;                                                 // 잠금 만료 — 리셋 후 새로 카운트
            }
            int fails = (e == null ? 0 : e.fails()) + 1;
            Instant lockedUntil = null;
            if (fails >= maxFails) {
                lockedUntil = now.plus(LOCK_DURATION);
                transition[0] = true;
            }
            return new Entry(fails, lockedUntil, now);
        });
        return transition[0];
    }

    /** 항목 수 상한 초과 시 오래된(만료·비활동) 항목 제거. */
    private void sweepIfOverflow() {
        if (entries.size() <= MAX_ENTRIES) return;
        Instant now = Instant.now(clock);
        Instant cutoff = now.minus(LOCK_DURATION);
        entries.entrySet().removeIf(en ->
            en.getValue().touchedAt().isBefore(cutoff)
                && (en.getValue().lockedUntil() == null
                    || en.getValue().lockedUntil().isBefore(now)));
    }

    private static String key(String scope, String kind, String value) {
        return scope + "|" + kind + "|" + value;
    }
}
