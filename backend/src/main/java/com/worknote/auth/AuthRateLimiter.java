package com.worknote.auth;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
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
    /** 메모리 상한 — 초과 시 만료 항목 정리 후 강제 축출. 키 폭탄(무작위 계정명 대량 시도)로 인한 힙 고갈 방지. */
    static final int MAX_ENTRIES = 10_000;
    /** 축출 목표치 — 상한까지만 지우면 매 실패마다 재정렬하게 되므로 여유를 두고 한 번에 정리(히스테리시스). */
    private static final int EVICT_TARGET = MAX_ENTRIES * 9 / 10;

    /** seq = touch 순번. touchedAt은 시계 해상도 때문에 동률이 생기고, 동률을 키 문자열로 깨면
     *  공격자가 계정명으로 축출 순서를 고를 수 있다(피해자보다 뒤에 오는 이름으로 도배). 순번은 그 여지를 없앤다. */
    private record Entry(int fails, Instant lockedUntil, Instant touchedAt, long seq) {}

    private final Clock clock;
    private final Map<String, Entry> entries = new ConcurrentHashMap<>();
    private final AtomicLong seqGen = new AtomicLong();
    private final AtomicBoolean sweeping = new AtomicBoolean(false);

    public AuthRateLimiter(Clock clock) {
        this.clock = clock;
    }

    /** 잠금 여부 — 계정 키 또는 IP 키 중 하나라도 잠겨 있으면 true. */
    public boolean isLocked(String scope, String accountKey, String ip) {
        return lockedNow(key(scope, "acct", accountKey)) || lockedNow(key(scope, "ip", ip));
    }

    /** 실패 기록 — 계정·IP 카운터 동시 증가. 이번 실패로 잠금이 '시작'되면 true(감사 로그 트리거용). */
    public boolean recordFailure(String scope, String accountKey, String ip) {
        boolean acctTransition = bump(key(scope, "acct", accountKey), ACCOUNT_MAX_FAILS);
        boolean ipTransition = bump(key(scope, "ip", ip), IP_MAX_FAILS);
        sweepIfOverflow();   // 증가 후에 정리 — 반환 시점에 항목 수 <= MAX_ENTRIES가 실제로 보장되게
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

    /** 보유 항목 수 — 상한 회귀 테스트 관측용. */
    int entryCount() {
        return entries.size();
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
            return new Entry(fails, lockedUntil, now, seqGen.incrementAndGet());
        });
        return transition[0];
    }

    /**
     * 항목 수 상한 유지 — ① 오래된(만료·비활동) 항목 제거, ② 그래도 초과면 강제 축출.
     * ①만으로는 상한이 아니다: 분산 버스트는 모든 항목을 계속 '방금 touch' 상태로 유지해
     * 나이 기준에 아무것도 걸리지 않는다. 그래서 ②가 실제 상한을 만든다.
     */
    private void sweepIfOverflow() {
        if (entries.size() <= MAX_ENTRIES) return;
        if (!sweeping.compareAndSet(false, true)) return;   // 동시 스윕 1개로 제한 — 과다 축출·중복 정렬 방지
        try {
            Instant now = Instant.now(clock);
            Instant cutoff = now.minus(LOCK_DURATION);
            entries.entrySet().removeIf(en ->
                en.getValue().touchedAt().isBefore(cutoff)
                    && (en.getValue().lockedUntil() == null
                        || en.getValue().lockedUntil().isBefore(now)));
            if (entries.size() > MAX_ENTRIES) evictOldest(entries.size() - EVICT_TARGET, now);
        } finally {
            sweeping.set(false);
        }
    }

    /**
     * 결정적 축출 — 미잠금 우선, 그 안에서 가장 오래 touch된 것부터(LRU).
     * 잠금 항목을 뒤로 미루는 이유: 잠금 조기 해제는 곧 시도제한 우회이므로,
     * 공격자가 특정 피해자의 잠금을 밀어내려면 미잠금 항목을 전부 소진시켜야 하도록 만든다.
     */
    private void evictOldest(int count, Instant now) {
        entries.entrySet().stream()
            .sorted(Comparator
                .<Map.Entry<String, Entry>, Boolean>comparing(en -> {
                    Instant until = en.getValue().lockedUntil();
                    return until != null && now.isBefore(until);
                })
                .thenComparingLong(en -> en.getValue().seq()))
            .limit(count)
            // 스냅샷 이후 갱신된 항목은 값이 달라져 제거되지 않음 — 활성 카운터를 실수로 날리지 않게
            .forEach(en -> entries.remove(en.getKey(), en.getValue()));
    }

    private static String key(String scope, String kind, String value) {
        return scope + "|" + kind + "|" + value;
    }
}
