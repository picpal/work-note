package com.worknote.auth;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Predicate;
import org.springframework.stereotype.Component;

/**
 * 인증 시도 제한 (CWE-307). 계정·IP 키별 연속 실패 카운터 — 임계 초과 시 일시 잠금.
 * 인메모리 단일 인스턴스 전제(폐쇄망 소규모, 재기동 시 초기화 수용).
 * 계정 키는 엄격(5회), IP 키는 프록시/NAT 오탐을 줄이기 위해 느슨(30회 — 계정 스프레이 방어).
 *
 * <p>동시성은 단일 모니터 락(공개 메서드 전부 synchronized)으로 처리한다. 락 없는 자료구조로는
 * 항목 수 상한을 증명할 수 없다: 축출량을 미리 계산하는 사이에도 유입이 계속되고, 정리를 건너뛴
 * 호출자는 그냥 넣고 나가므로 상한이 '사후에 대체로' 지켜지는 성질로 약해진다. 반면 이 경로는
 * 로그인·2FA·복구 시도 전용이라 처리량이 병목이 될 일이 없다(DB 커넥션 풀 크기부터 1이다).
 */
@Component
public class AuthRateLimiter {

    public static final int ACCOUNT_MAX_FAILS = 5;
    public static final int IP_MAX_FAILS = 30;
    public static final Duration LOCK_DURATION = Duration.ofMinutes(5);
    /**
     * 항목 수 상한 — 키 폭탄(무작위 계정명 대량 시도)으로 인한 힙 고갈 방지.
     * 유입과 축출이 같은 락 안에서 끝나므로, 다른 스레드가 관측할 수 있는 크기는 항상 이 값 이하다.
     */
    static final int MAX_ENTRIES = 10_000;
    /** 축출 목표치 — 상한까지만 지우면 매 실패마다 다시 훑게 되므로 여유를 두고 한 번에 정리(히스테리시스). */
    private static final int EVICT_TARGET = MAX_ENTRIES * 9 / 10;

    private record Entry(int fails, Instant lockedUntil) {}

    private final Clock clock;
    /**
     * 삽입 순서 = touch 순서. 카운터를 갱신할 때 remove 후 put 하므로 갱신된 항목이 맨 뒤로 가고,
     * 따라서 앞에서부터 훑는 것이 곧 '가장 오래 방치된 것부터'(LRU)가 된다.
     * 순서를 시각이나 키 문자열로 정하지 않는 게 핵심 — 시각은 해상도 때문에 동률이 생기고,
     * 동률을 키로 깨면 공격자가 피해자보다 뒤에 오는 이름으로 도배해 피해자 카운터를 골라 축출할 수 있다.
     */
    private final Map<String, Entry> entries = new LinkedHashMap<>();

    public AuthRateLimiter(Clock clock) {
        this.clock = clock;
    }

    /** 잠금 여부 — 계정 키 또는 IP 키 중 하나라도 잠겨 있으면 true. */
    public synchronized boolean isLocked(String scope, String accountKey, String ip) {
        Instant now = Instant.now(clock);
        return lockedNow(key(scope, "acct", accountKey), now) || lockedNow(key(scope, "ip", ip), now);
    }

    /** 실패 기록 — 계정·IP 카운터 동시 증가. 이번 실패로 잠금이 '시작'되면 true(감사 로그 트리거용). */
    public synchronized boolean recordFailure(String scope, String accountKey, String ip) {
        Instant now = Instant.now(clock);
        boolean acctTransition = bump(key(scope, "acct", accountKey), ACCOUNT_MAX_FAILS, now);
        boolean ipTransition = bump(key(scope, "ip", ip), IP_MAX_FAILS, now);
        evictIfOverflow(now);   // 유입과 같은 임계구역에서 정리 — 락을 놓는 시점에 상한이 이미 성립해 있게
        return acctTransition || ipTransition;
    }

    /** 성공 시 계정 카운터 해제. IP 카운터는 유지 — 성공 1회로 스프레이 카운터가 씻기지 않게. */
    public synchronized void recordSuccess(String scope, String accountKey) {
        entries.remove(key(scope, "acct", accountKey));
    }

    /** 전체 초기화 — 통합 테스트 격리용. */
    public synchronized void clearAll() {
        entries.clear();
    }

    /** 보유 항목 수 — 상한 회귀 테스트 관측용. */
    synchronized int entryCount() {
        return entries.size();
    }

    private boolean lockedNow(String key, Instant now) {
        Entry e = entries.get(key);
        if (e == null || e.lockedUntil() == null) return false;
        if (!now.isBefore(e.lockedUntil())) {
            entries.remove(key);   // 만료 — lazy 정리 + 카운터 리셋
            return false;
        }
        return true;
    }

    /** @return 이번 실패로 잠금이 '시작'됐으면 true. 이미 잠금 중이면 상태 유지하고 false. */
    private boolean bump(String key, int maxFails, Instant now) {
        Entry current = entries.get(key);
        if (current != null && current.lockedUntil() != null) {
            if (now.isBefore(current.lockedUntil())) return false;   // 잠금 중 — 카운터도 순서도 그대로
            current = null;                                          // 잠금 만료 — 리셋 후 새로 카운트
        }
        int fails = (current == null ? 0 : current.fails()) + 1;
        Instant lockedUntil = fails >= maxFails ? now.plus(LOCK_DURATION) : null;
        entries.remove(key);                                 // remove 후 put = LRU 순서상 맨 뒤로 이동
        entries.put(key, new Entry(fails, lockedUntil));
        return lockedUntil != null;
    }

    /**
     * 상한 유지 — 미잠금 항목을 먼저, 그래도 모자랄 때만 잠금 항목까지 오래된 순으로 축출.
     * 잠금을 뒤로 미루는 이유: 잠금 조기 해제는 곧 시도제한 우회이므로, 공격자가 특정 피해자의 잠금을
     * 밀어내려면 미잠금 항목을 전부 소진시켜야 하도록 만든다.
     * 나이 기준 별도 정리는 두지 않는다 — 만료된 잠금은 아래 ①에서 이미 미잠금 취급이고,
     * 오래된 항목은 LRU 순서상 어차피 맨 앞이라 같은 결과가 나온다.
     */
    private void evictIfOverflow(Instant now) {
        if (entries.size() <= MAX_ENTRIES) return;
        evictOldest(e -> e.lockedUntil() == null || !now.isBefore(e.lockedUntil()));   // ① 미잠금·만료
        evictOldest(e -> true);                                                        // ② 최후 수단
    }

    /** 오래된 순으로 훑으며 조건에 맞는 항목을 목표치까지 제거. ②가 무조건 참이라 상한 도달이 보장된다. */
    private void evictOldest(Predicate<Entry> evictable) {
        Iterator<Entry> it = entries.values().iterator();
        while (entries.size() > EVICT_TARGET && it.hasNext()) {
            if (evictable.test(it.next())) it.remove();
        }
    }

    private static String key(String scope, String kind, String value) {
        return scope + "|" + kind + "|" + value;
    }
}
