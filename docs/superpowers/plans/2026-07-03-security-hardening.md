# 보안 점검 후속 하드닝 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `docs/security/2026-07-03-security-audit.md`의 조치 필요 항목(HIGH 1 · MEDIUM 2 · SCA 39 · Low 4 · Info 1)을 TDD로 해소한다.

**Architecture:** 백엔드는 기존 레이어(Controller → Service → Mapper)와 예외 계약(`{"error": msg}`)을 유지하며 순수 클래스(AuthRateLimiter, RedmineUrlValidator, RecoveryCodec)를 추가해 유닛 테스트 가능하게 만든다. 프런트는 복구 UI 마크업 보정 + 순수 함수 1개 추가만. Spring Security는 도입하지 않는다(기존 필터 구조 유지).

**Tech Stack:** Java 21 + Spring Boot 3.5.x + MyBatis + SQLite(백엔드), Vite + React 18 + TypeScript(프런트, JSX 미사용 — `React.createElement`), JUnit5 + MockMvc / Vitest.

## Global Constraints

- 백엔드 통합테스트의 인메모리 SQLite는 **반드시 네임드 DB**: `jdbc:sqlite:file:memdb-<고유이름>?mode=memory&cache=shared` (익명 `::memory:?cache=shared`는 JVM 전역 공유 → 타 테스트와 PK 충돌)
- 프런트 컴포넌트는 **JSX 미사용** — `const h = React.createElement` 관례. 테스트는 결정 로직을 순수 함수로 추출해 vitest 유닛만(React 렌더 테스트 금지)
- API 오류 본문은 `{"error": "<메시지>"}` 단일 계약 유지
- 커밋 메시지: 한국어 + conventional prefix (예: `fix(auth): ...`, `chore(deps): ...`)
- 테스트 명령: 백엔드 `cd backend && ./gradlew test`, 프런트 `cd frontend && pnpm test`
- 각 태스크 완료 시점에 백엔드 전체 테스트 그린 유지 (기존 291/179 테스트 회귀 금지)

## 감사 문서와 다른 설계 결정 (근거)

1. **SSRF(§2-2): 사설 IP 대역(10/8, 172.16/12, 192.168/16)은 차단하지 않는다** — 폐쇄망 인트라넷 Redmine이 정상 사용처(예: `http://redmine.intra`, `http://10.x.x.x`)라 차단 시 기능 자체가 불능. 차단 대상은 loopback·link-local(클라우드 메타데이터 169.254.169.254 포함)·any-local(0.0.0.0)·multicast. `https` 강제도 하지 않는다(폐쇄망 HTTP 전제).
2. **복구코드(§2-3) 1차 요소 = 기존 pending 세션 재사용** — 별도 비밀번호 재입력 대신, 비밀번호 로그인으로 만들어진 `SESSION_2FA_PENDING` 세션을 요구한다. `verify2fa`와 동일한 게이팅 패턴이고 프런트 플로우(로그인 → OTP 화면 → "복구 코드로 로그인")와 자연스럽게 일치한다.
3. **공유 첨부(§4): 이미지 한정으로 확정** — `SharePage.tsx`는 본문 인라인 이미지 src 재작성만 하고 첨부 목록/다운로드 UI가 없다. 비이미지 서빙은 사용처 없는 반출 통로이므로 404로 닫는다. maxViews는 노트 resolve에서만 소모(이미지 로드는 노트 1회 열람의 구성 요소 — 다중 이미지 노트에서 이중 소모 방지).
4. **의도적 미조치(Info 2건)**: CSP `unsafe-inline/unsafe-eval`(mermaid 요구 — 문서화된 트레이드오프), 업로드 매직바이트 검증(서버파생 MIME + nosniff + attachment로 XSS 미유발 — 잔여 갭 수용). Task 13 문서에 기록만 한다.

---

### Task 1: Spring Boot 3.5.14 업그레이드 (SCA 39건 해소)

**Files:**
- Modify: `backend/build.gradle`

**Interfaces:**
- Consumes: 없음
- Produces: 이후 모든 태스크가 이 버전 위에서 빌드/테스트됨

- [ ] **Step 1: 업그레이드 전 현재 테스트 그린 확인 (베이스라인)**

Run: `cd backend && ./gradlew test`
Expected: BUILD SUCCESSFUL (기존 전체 그린)

- [ ] **Step 2: build.gradle 버전 변경**

`backend/build.gradle`의 plugins 블록 변경:

```groovy
plugins {
    id 'java'
    id 'org.springframework.boot' version '3.5.14'
    id 'io.spring.dependency-management' version '1.1.7'
}
```

그리고 `repositories` 블록 위에 assertj 버전 오버라이드 추가 (CVE-2026-24400, test 전용):

```groovy
ext['assertj.version'] = '3.27.7'
```

- [ ] **Step 3: 해소 대상 의존성의 해석(resolved) 버전 확인**

Run: `cd backend && ./gradlew dependencies --configuration runtimeClasspath | grep -E "tomcat-embed-core|jackson-databind|spring-core|spring-web|logback-core" | sort -u`

수정 하한(감사 문서 §3 기준)과 비교:

| 컴포넌트 | 하한 |
|---|---|
| tomcat-embed-core | 10.1.55 |
| jackson-databind | 2.18.8(2.18계) 또는 2.21.4(2.21계) |
| spring-core | 6.2.11 |
| logback-core | 1.5.33 |

하한 미달 컴포넌트가 있으면 `build.gradle`에 해당 BOM 프로퍼티만 추가 (예시 — 실제 미달인 것만):

```groovy
ext['tomcat.version'] = '10.1.55'
ext['jackson-bom.version'] = '2.21.4'
ext['logback.version'] = '1.5.33'
```

오버라이드 추가 후 Step 3 명령 재실행으로 반영 확인.

- [ ] **Step 4: 전체 테스트로 호환성 검증**

Run: `cd backend && ./gradlew clean test`
Expected: BUILD SUCCESSFUL. 실패 시 실패 테스트를 보고 원인(BOM 버전 충돌 vs 앱 코드)을 판별 — jackson 2.21 계열이 비호환이면 `ext['jackson-bom.version'] = '2.18.8'`로 낮춰 재시도.

- [ ] **Step 5: Commit**

```bash
git add backend/build.gradle
git commit -m "chore(deps): Spring Boot 3.5.0 → 3.5.14 — SCA 39건(심각 3 포함) 일괄 해소"
```

---

### Task 2: AuthRateLimiter — 인증 시도 제한 순수 클래스 (HIGH §2-1 코어)

**Files:**
- Create: `backend/src/main/java/com/worknote/auth/AuthRateLimiter.java`
- Test: `backend/src/test/java/com/worknote/auth/AuthRateLimiterTest.java`

**Interfaces:**
- Consumes: `java.time.Clock` (생성자 주입 — `WorknoteApplication`에 기존 Clock 빈 존재)
- Produces (Task 3이 사용):
  - `boolean isLocked(String scope, String accountKey, String ip)`
  - `boolean recordFailure(String scope, String accountKey, String ip)` — 잠금 **전이** 시 true(감사 로그 트리거)
  - `void recordSuccess(String scope, String accountKey)`
  - `void clearAll()` — 테스트 격리용
  - 상수: `ACCOUNT_MAX_FAILS=5`, `IP_MAX_FAILS=30`, `LOCK_DURATION=Duration.ofMinutes(5)`

- [ ] **Step 1: 실패하는 테스트 작성**

`backend/src/test/java/com/worknote/auth/AuthRateLimiterTest.java`:

```java
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
```

- [ ] **Step 2: 실패 확인**

Run: `cd backend && ./gradlew test --tests AuthRateLimiterTest`
Expected: 컴파일 실패 — `AuthRateLimiter` 미존재

- [ ] **Step 3: 구현**

`backend/src/main/java/com/worknote/auth/AuthRateLimiter.java`:

```java
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
            entries.remove(key);   // 만료 — lazy 정리 + 카운터 리셋
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
        Instant cutoff = Instant.now(clock).minus(LOCK_DURATION);
        entries.entrySet().removeIf(en ->
            en.getValue().touchedAt().isBefore(cutoff)
                && (en.getValue().lockedUntil() == null
                    || en.getValue().lockedUntil().isBefore(Instant.now(clock))));
    }

    private static String key(String scope, String kind, String value) {
        return scope + "|" + kind + "|" + value;
    }
}
```

- [ ] **Step 4: 테스트 통과 확인**

Run: `cd backend && ./gradlew test --tests AuthRateLimiterTest`
Expected: 7 tests PASS

주의: `fifthFailure_locksAccount_andReportsTransition`에서 "잠금 중 추가 실패는 전이 아님" 검증 — 잠금 중 재호출은 compute 람다가 기존 Entry를 그대로 반환해 `transition[0]`이 false로 남아야 한다. 시각 비교(touchedAt == now)로 전이를 판정하면 같은 Instant의 재실패가 전이로 오판되므로 금지.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/worknote/auth/AuthRateLimiter.java backend/src/test/java/com/worknote/auth/AuthRateLimiterTest.java
git commit -m "feat(auth): AuthRateLimiter — 계정 5회·IP 30회 실패 시 5분 잠금 (CWE-307 코어)"
```

---

### Task 3: 인증 엔드포인트 rate-limit 연결 + 429 매핑 (HIGH §2-1 완성)

**Files:**
- Modify: `backend/src/main/java/com/worknote/auth/AuthException.java`
- Modify: `backend/src/main/java/com/worknote/ApiExceptionHandler.java`
- Modify: `backend/src/main/java/com/worknote/auth/AuthController.java` (login·verify2fa·recoverVerify)
- Test: `backend/src/test/java/com/worknote/auth/AuthRateLimitApiTest.java`

**Interfaces:**
- Consumes: Task 2의 `AuthRateLimiter` (isLocked/recordFailure/recordSuccess/clearAll)
- Produces: 잠금 시 HTTP 429 + `{"error": "시도 횟수를 초과했습니다. 잠시 후 다시 시도하세요"}`, 감사 액션 `auth.lockout`(잠금 전이)·`login.locked`/`2fa.locked`/`recover.locked`(잠금 중 시도)

- [ ] **Step 1: 실패하는 API 테스트 작성**

`backend/src/test/java/com/worknote/auth/AuthRateLimitApiTest.java`:

```java
package com.worknote.auth;

import com.worknote.auth.totp.Totp;
import com.worknote.auth.totp.TotpService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:sqlite:file:memdb-ratelimit?mode=memory&cache=shared",
    "worknote.mode=server", "worknote.admin-password=x",
    "worknote.totp.key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
})
@AutoConfigureMockMvc
class AuthRateLimitApiTest {

    @Autowired MockMvc mvc;
    @Autowired UserMapper users;
    @Autowired TotpService totp;
    @Autowired JdbcTemplate jdbc;
    @Autowired AuthRateLimiter limiter;

    @BeforeEach void clean() {
        limiter.clearAll();
        jdbc.update("DELETE FROM totp_recovery");
        jdbc.update("DELETE FROM user_totp");
        jdbc.update("DELETE FROM user_credential");
        jdbc.update("DELETE FROM app_user");
        users.insert(new UserRow("u1", "10001", "a@corp.local", "홍", "operator", "active", null));
        String salt = PasswordHasher.newSalt();
        users.insertCredential(new CredentialRow("u1", salt, PasswordHasher.hash("pw-1234", salt)));
    }

    private void failLogin(String emp) throws Exception {
        mvc.perform(post("/api/auth/login").contentType(APPLICATION_JSON)
            .content("{\"emp\":\"" + emp + "\",\"password\":\"wrong\"}"))
            .andExpect(status().isUnauthorized());
    }

    @Test void sixthLoginAttempt_returns429_evenWithCorrectPassword() throws Exception {
        for (int i = 0; i < 5; i++) failLogin("10001");
        // 6번째 — 올바른 비밀번호여도 잠금 중이면 429 (잠금 중 크리덴셜 확인 자체를 차단)
        mvc.perform(post("/api/auth/login").contentType(APPLICATION_JSON)
            .content("{\"emp\":\"10001\",\"password\":\"pw-1234\"}"))
            .andExpect(status().isTooManyRequests())
            .andExpect(jsonPath("$.error").exists());
    }

    @Test void lockIsPerAccount_otherAccountStillWorks() throws Exception {
        users.insert(new UserRow("u2", "10002", null, "김", "operator", "active", null));
        String salt = PasswordHasher.newSalt();
        users.insertCredential(new CredentialRow("u2", salt, PasswordHasher.hash("pw-5678", salt)));
        for (int i = 0; i < 5; i++) failLogin("10001");
        mvc.perform(post("/api/auth/login").contentType(APPLICATION_JSON)
            .content("{\"emp\":\"10002\",\"password\":\"pw-5678\"}"))
            .andExpect(status().isOk());
    }

    @Test void successfulLoginClearsCounter() throws Exception {
        for (int i = 0; i < 4; i++) failLogin("10001");
        mvc.perform(post("/api/auth/login").contentType(APPLICATION_JSON)
            .content("{\"emp\":\"10001\",\"password\":\"pw-1234\"}"))
            .andExpect(status().isOk());
        // 성공으로 리셋 — 이후 1회 실패로 잠기지 않음
        failLogin("10001");
        mvc.perform(post("/api/auth/login").contentType(APPLICATION_JSON)
            .content("{\"emp\":\"10001\",\"password\":\"pw-1234\"}"))
            .andExpect(status().isOk());
    }

    @Test void fiveWrong2faCodes_locksVerify() throws Exception {
        totp.setup("u1", "10001");
        totp.confirm("u1", Totp.codeAt(totp.currentSecretForTest("u1"),
            java.time.Instant.now().getEpochSecond()));
        MockHttpSession s = new MockHttpSession();
        mvc.perform(post("/api/auth/login").session(s).contentType(APPLICATION_JSON)
            .content("{\"emp\":\"10001\",\"password\":\"pw-1234\"}"))
            .andExpect(jsonPath("$.status").value("2fa_required"));
        for (int i = 0; i < 5; i++) {
            mvc.perform(post("/api/auth/2fa/verify").session(s).contentType(APPLICATION_JSON)
                .content("{\"code\":\"000000\"}"))
                .andExpect(status().isUnauthorized());
        }
        // 6번째 — 잠금 (올바른 코드여도 검증 시도 자체가 차단돼야 함)
        String valid = Totp.codeAt(totp.currentSecretForTest("u1"),
            java.time.Instant.now().getEpochSecond());
        mvc.perform(post("/api/auth/2fa/verify").session(s).contentType(APPLICATION_JSON)
            .content("{\"code\":\"" + valid + "\"}"))
            .andExpect(status().isTooManyRequests());
    }
}
```

주의: `totp.currentSecretForTest`·`Totp.codeAt`은 기존 `Totp2faRecoverApiTest`가 쓰는 실존 헬퍼다. 2FA를 켠 사용자의 시드가 disable 후 재조회되는 타이밍이 있으므로 valid 코드 생성은 잠금 검증 직전에 한다.

- [ ] **Step 2: 실패 확인**

Run: `cd backend && ./gradlew test --tests AuthRateLimitApiTest`
Expected: FAIL — 429 대신 401 반환 (`isTooManyRequests` 불일치), `status().isTooManyRequests()` 단계에서 실패

- [ ] **Step 3: AuthException에 LOCKED 추가**

`backend/src/main/java/com/worknote/auth/AuthException.java` — enum과 팩토리 확장:

```java
public enum Status { UNAUTHORIZED, FORBIDDEN, LOCKED }
```

```java
    public static AuthException locked(String message) {
        return new AuthException(Status.LOCKED, message);
    }
```

`backend/src/main/java/com/worknote/ApiExceptionHandler.java`의 auth 핸들러 switch 확장:

```java
    @ExceptionHandler(AuthException.class)
    public ResponseEntity<Map<String, String>> auth(AuthException e) {
        HttpStatus status = switch (e.status()) {
            case UNAUTHORIZED -> HttpStatus.UNAUTHORIZED;
            case FORBIDDEN -> HttpStatus.FORBIDDEN;
            case LOCKED -> HttpStatus.TOO_MANY_REQUESTS;
        };
        return ResponseEntity.status(status).body(Map.of("error", e.getMessage()));
    }
```

- [ ] **Step 4: AuthController 연결**

`AuthController`에 필드·생성자 파라미터 `AuthRateLimiter limiter` 추가 후 3개 메서드 수정.

`login` (기존 71–101행 대체):

```java
    @PostMapping("/login")
    public Object login(@Valid @RequestBody LoginRequest req, HttpServletRequest http) {
        String ip = http.getRemoteAddr();
        if (limiter.isLocked("login", req.emp(), ip)) {
            audit.logRaw(req.emp(), "login.locked", null, ip);
            throw AuthException.locked("시도 횟수를 초과했습니다. 잠시 후 다시 시도하세요");
        }
        AuthService.AuthUser result;
        try {
            result = auth.login(req.emp(), req.password());
        } catch (AuthException e) {
            if (limiter.recordFailure("login", req.emp(), ip)) {
                audit.logRaw(req.emp(), "auth.lockout", "login", ip);
            }
            audit.logRaw(req.emp(), "login.fail", null, ip);   // 실패도 항상 기록 (스펙 §7)
            throw e;
        }
        limiter.recordSuccess("login", req.emp());
        HttpSession session = http.getSession(true);
        http.changeSessionId();   // 세션 고정 방어 — 공용 PC 교대 로그인 시 세션 id 재사용 방지 (내용 유지, id만 교체)
        session.setAttribute(SESSION_USER, result.user().id());
        // ... (이하 기존 코드 그대로: admin grace_start 보장, 2FA pending 분기, SESSION_CRED 설정)
```

`verify2fa` (기존 103–115행 대체):

```java
    @PostMapping("/2fa/verify")
    public MeResponse verify2fa(@Valid @RequestBody TotpVerifyRequest req, HttpServletRequest http) {
        HttpSession session = http.getSession(false);
        String userId = session != null ? (String) session.getAttribute(SESSION_USER) : null;
        if (userId == null || !Boolean.TRUE.equals(session.getAttribute(SESSION_2FA_PENDING))) {
            throw AuthException.unauthorized("2FA 인증 대기 상태가 아닙니다");
        }
        String ip = http.getRemoteAddr();
        if (limiter.isLocked("2fa", userId, ip)) {
            audit.logRaw(userId, "2fa.locked", null, ip);
            throw AuthException.locked("시도 횟수를 초과했습니다. 잠시 후 다시 시도하세요");
        }
        if (!totpService.verifyLogin(userId, req.code())) {
            if (limiter.recordFailure("2fa", userId, ip)) {
                audit.logRaw(userId, "auth.lockout", "2fa", ip);
            }
            audit.logRaw(userId, "2fa.verify.fail", null, ip);
            throw AuthException.unauthorized("인증 코드가 올바르지 않습니다");
        }
        limiter.recordSuccess("2fa", userId);
        return completePending(session, userId, http, "2fa.verify.success");
    }
```

`recoverVerify` — 메서드 진입 직후(기존 `recoveryService.verify` 호출 전)에 삽입, 실패 분기와 성공 지점에 기록:

```java
        String ip = http.getRemoteAddr();
        if (limiter.isLocked("recover", req.emp(), ip)) {
            audit.logRaw(req.emp(), "recover.locked", null, ip);
            throw AuthException.locked("시도 횟수를 초과했습니다. 잠시 후 다시 시도하세요");
        }
        String userId = recoveryService.verify(req.emp(), req.code());
        if (userId == null) {
            if (limiter.recordFailure("recover", req.emp(), ip)) {
                audit.logRaw(req.emp(), "auth.lockout", "recover", ip);
            }
            audit.logRaw(req.emp(), "2fa.recover.fail", null, ip);
            throw AuthException.unauthorized("복구 코드가 올바르지 않거나 만료되었습니다");
        }
        // (이하 기존 코드) — user/cred 확인 후 성공 직전에:
        limiter.recordSuccess("recover", req.emp());
```

- [ ] **Step 5: 새 테스트 + 전체 테스트 통과 확인**

Run: `cd backend && ./gradlew test`
Expected: BUILD SUCCESSFUL. 기존 테스트 중 같은 계정으로 로그인 실패를 5회 이상 반복하는 테스트가 있으면 429로 깨진다 — 해당 테스트 클래스 `@BeforeEach`에 `@Autowired AuthRateLimiter limiter; limiter.clearAll();`를 추가해 격리 (테스트 파일만 수정, 프로덕션 로직 변경 금지).

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/worknote/auth/ backend/src/main/java/com/worknote/ApiExceptionHandler.java backend/src/test/java/com/worknote/auth/
git commit -m "fix(auth): 로그인·2FA·복구 시도 제한 — 잠금 시 429 + auth.lockout 감사 (HIGH CWE-307)"
```

---

### Task 4: 복구 코드 엔트로피 상향 — 8자리 숫자 → 12자 영숫자 (MEDIUM §2-3 일부)

**Files:**
- Modify: `backend/src/main/java/com/worknote/auth/totp/RecoveryCodec.java`
- Modify: `backend/src/main/java/com/worknote/auth/totp/RecoveryService.java` (verify에 normalize 적용)
- Modify: `backend/src/test/java/com/worknote/auth/totp/RecoveryCodecTest.java`
- Modify: `backend/src/test/java/com/worknote/auth/Totp2faRecoverApiTest.java` (코드 추출 방식)
- Modify: `backend/src/test/java/com/worknote/auth/totp/RecoveryServiceTest.java` (숫자 8자리 가정이 있으면)

**Interfaces:**
- Consumes: 없음
- Produces: `RecoveryCodec.generate()` → 12자, 알파벳 `ABCDEFGHJKMNPQRSTUVWXYZ23456789`(혼동문자 제외 31자, ≈59.4bit) / `RecoveryCodec.normalize(String)` → 공백·하이픈 제거 + 대문자화 (Task 6 프런트가 동일 규칙 미러링)

- [ ] **Step 1: 실패하는 테스트 작성**

`RecoveryCodecTest.java`를 다음 내용으로 대체(기존 8자리 가정 테스트 제거):

```java
package com.worknote.auth.totp;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class RecoveryCodecTest {

    @Test void generate_is12CharsFromUnambiguousAlphabet() {
        for (int i = 0; i < 100; i++) {
            String code = RecoveryCodec.generate();
            assertThat(code).hasSize(12).matches("[ABCDEFGHJKMNPQRSTUVWXYZ23456789]+");
        }
    }

    @Test void generate_isNotRepeating() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 100; i++) seen.add(RecoveryCodec.generate());
        assertThat(seen).hasSizeGreaterThan(95);
    }

    @Test void normalize_stripsSeparatorsAndUppercases() {
        assertThat(RecoveryCodec.normalize(" abcd-2345 efgh ")).isEqualTo("ABCD2345EFGH");
        assertThat(RecoveryCodec.normalize(null)).isEmpty();
    }
}
```

- [ ] **Step 2: 실패 확인**

Run: `cd backend && ./gradlew test --tests RecoveryCodecTest`
Expected: FAIL — 길이 8 ≠ 12, `normalize` 미존재(컴파일 실패)

- [ ] **Step 3: 구현**

`RecoveryCodec.java` 전체 대체:

```java
package com.worknote.auth.totp;

import java.security.SecureRandom;

/**
 * 이메일 1회용 복구 코드 — 12자 영숫자(혼동문자 0/O·1/I/L 제외 31자, ≈59.4bit).
 * 기존 8자리 숫자(≈26.6bit)의 브루트포스 여지를 제거. 해시 저장은 PasswordHasher(PBKDF2) 재사용.
 */
public final class RecoveryCodec {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final char[] ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789".toCharArray();
    public static final int LENGTH = 12;

    private RecoveryCodec() {}

    public static String generate() {
        StringBuilder sb = new StringBuilder(LENGTH);
        for (int i = 0; i < LENGTH; i++) {
            sb.append(ALPHABET[RANDOM.nextInt(ALPHABET.length)]);
        }
        return sb.toString();
    }

    /** 사용자 입력 정규화 — 공백·하이픈 제거 + 대문자화 (이메일에서 복사 시 흔한 변형 흡수). */
    public static String normalize(String input) {
        return input == null ? "" : input.replaceAll("[\\s-]", "").toUpperCase();
    }
}
```

`RecoveryService.verify`의 해시 검증 한 줄 수정:

```java
        if (!PasswordHasher.verify(RecoveryCodec.normalize(code), rc.salt(), rc.codeHash())) return null;
```

- [ ] **Step 4: 연쇄 테스트 보정**

`Totp2faRecoverApiTest.java`의 코드 추출 2곳(`BODY.get().replaceAll("[^0-9]","").substring(0,8)`)을 다음으로 교체:

```java
        String code = BODY.get().replaceAll("(?s).*복구 코드: (\\S+).*", "$1");
```

`RecoveryServiceTest.java`에 8자리 숫자 형식을 가정한 검증이 있으면 같은 방식으로 교체.

- [ ] **Step 5: 전체 테스트 통과 확인**

Run: `cd backend && ./gradlew test --tests "com.worknote.auth.*"`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/worknote/auth/totp/ backend/src/test/java/com/worknote/auth/
git commit -m "fix(auth): 복구 코드 12자 영숫자로 엔트로피 상향(26.6→59.4bit) + 입력 정규화"
```

---

### Task 5: 복구 엔드포인트에 1차 요소(pending 세션) 게이트 (MEDIUM §2-3 완성)

**Files:**
- Modify: `backend/src/main/java/com/worknote/auth/AuthController.java` (recoverRequest·recoverVerify)
- Modify: `backend/src/test/java/com/worknote/auth/Totp2faRecoverApiTest.java`

**Interfaces:**
- Consumes: 기존 세션 상수 `SESSION_USER`/`SESSION_2FA_PENDING`, Task 3의 limiter 코드(recoverVerify 내 유지)
- Produces: `/2fa/recover/request`·`/2fa/recover/verify`는 **비밀번호 로그인으로 생성된 pending 세션 + emp 일치** 시에만 동작. 그 외 401 `{"error": "비밀번호 인증 후 복구 코드를 사용할 수 있습니다"}` (세션 없음/emp 불일치 균등 응답 — 계정 열거 차단)

- [ ] **Step 1: 실패하는 테스트 작성**

`Totp2faRecoverApiTest.java`의 테스트 메서드들을 다음으로 대체(클래스 헤더·FakeMail·@BeforeEach는 유지):

```java
    /** 복구 플로우 진입 헬퍼 — 비밀번호 로그인으로 pending 세션 생성. */
    private MockHttpSession pendingSession() throws Exception {
        MockHttpSession s = new MockHttpSession();
        mvc.perform(post("/api/auth/login").session(s).contentType(APPLICATION_JSON)
            .content("{\"emp\":\"10001\",\"password\":\"pw-1234\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("2fa_required"));
        return s;
    }

    private String mailedCode() {
        return BODY.get().replaceAll("(?s).*복구 코드: (\\S+).*", "$1");
    }

    @Test void requestWithoutPendingSession_returns401() throws Exception {
        mvc.perform(post("/api/auth/2fa/recover/request").contentType(APPLICATION_JSON)
            .content("{\"emp\":\"10001\"}")).andExpect(status().isUnauthorized());
    }

    @Test void verifyWithoutPendingSession_returns401() throws Exception {
        mvc.perform(post("/api/auth/2fa/recover/verify").contentType(APPLICATION_JSON)
            .content("{\"emp\":\"10001\",\"code\":\"ABCD2345EFGH\"}"))
            .andExpect(status().isUnauthorized());
    }

    @Test void requestForDifferentEmp_returns401_uniformMessage() throws Exception {
        MockHttpSession s = pendingSession();
        // pending은 10001 — 다른 사번으로 요청하면 세션없음 케이스와 동일한 401 (열거 차단)
        mvc.perform(post("/api/auth/2fa/recover/request").session(s).contentType(APPLICATION_JSON)
            .content("{\"emp\":\"99999\"}")).andExpect(status().isUnauthorized());
    }

    @Test void recoverFlow_fromPendingSession_succeedsAndDisablesTotp() throws Exception {
        MockHttpSession s = pendingSession();
        mvc.perform(post("/api/auth/2fa/recover/request").session(s).contentType(APPLICATION_JSON)
            .content("{\"emp\":\"10001\"}")).andExpect(status().isNoContent());
        mvc.perform(post("/api/auth/2fa/recover/verify").session(s).contentType(APPLICATION_JSON)
            .content("{\"emp\":\"10001\",\"code\":\"" + mailedCode() + "\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.emp").value("10001"))
            .andExpect(jsonPath("$.totp.enabled").value(false));   // 복구 = 2FA 폐기(재등록 강제)
        // 승격 후 보호 API 통과 — pending 마커가 제거됐어야 함
        mvc.perform(get("/api/auth/me").session(s))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.emp").value("10001"));
    }

    @Test void recoverRequest_stillSilentSkipsWhenNoEmail_returns204() throws Exception {
        // 이메일 없는 사용자 — pending까지 왔더라도 발송은 조용히 skip, 204 균등 응답 유지
        jdbc.update("UPDATE app_user SET email = NULL WHERE id = 'u1'");
        MockHttpSession s = pendingSession();
        mvc.perform(post("/api/auth/2fa/recover/request").session(s).contentType(APPLICATION_JSON)
            .content("{\"emp\":\"10001\"}")).andExpect(status().isNoContent());
        org.assertj.core.api.Assertions.assertThat(BODY.get()).isNull();
    }
```

기존 `requestAlwaysReturns204_evenUnknownEmp`, `verifyValidCodeLogsInAndDisablesTotp`, `verifyFromPendingSession_promotesAndAllowsProtectedApi`는 삭제(새 테스트가 커버).

- [ ] **Step 2: 실패 확인**

Run: `cd backend && ./gradlew test --tests Totp2faRecoverApiTest`
Expected: FAIL — `requestWithoutPendingSession_returns401`이 204를 받아 실패

- [ ] **Step 3: AuthController 수정**

private 헬퍼 추가:

```java
    /**
     * 복구는 비밀번호 인증(pending 세션) 후에만 — 이메일 수신함 탈취 단독으로
     * 1차 요소(비밀번호)까지 우회하는 매직링크화 차단 (감사 §2-3).
     * 세션 없음·pending 아님·emp 불일치 모두 동일 401 (계정 열거 차단).
     */
    private String requirePendingFor(String emp, HttpServletRequest http) {
        HttpSession session = http.getSession(false);
        String userId = session != null ? (String) session.getAttribute(SESSION_USER) : null;
        if (userId == null || !Boolean.TRUE.equals(session.getAttribute(SESSION_2FA_PENDING))) {
            throw AuthException.unauthorized("비밀번호 인증 후 복구 코드를 사용할 수 있습니다");
        }
        UserRow u = users.findById(userId);
        if (u == null || !u.emp().equals(emp)) {
            throw AuthException.unauthorized("비밀번호 인증 후 복구 코드를 사용할 수 있습니다");
        }
        return userId;
    }
```

`recoverRequest` 첫 줄에 게이트 삽입:

```java
    @PostMapping("/2fa/recover/request")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void recoverRequest(@Valid @RequestBody RecoverRequest req, HttpServletRequest http) {
        requirePendingFor(req.emp(), http);   // 1차 요소 선행 — 복구는 2차 요소의 대체일 뿐
        recoveryService.request(req.emp());   // 내부에서 조건 미충족 시 skip — 균등 응답
        audit.logRaw(req.emp(), "2fa.recover.request", null, http.getRemoteAddr());
    }
```

`recoverVerify` — Task 3에서 수정한 버전 기준으로, limiter 검사 **앞에** 게이트 삽입 + 세션 생성을 기존 세션 재사용으로 변경:

```java
    @PostMapping("/2fa/recover/verify")
    public MeResponse recoverVerify(@Valid @RequestBody RecoverVerifyRequest req, HttpServletRequest http) {
        requirePendingFor(req.emp(), http);
        String ip = http.getRemoteAddr();
        if (limiter.isLocked("recover", req.emp(), ip)) {
            audit.logRaw(req.emp(), "recover.locked", null, ip);
            throw AuthException.locked("시도 횟수를 초과했습니다. 잠시 후 다시 시도하세요");
        }
        String userId = recoveryService.verify(req.emp(), req.code());
        if (userId == null) {
            if (limiter.recordFailure("recover", req.emp(), ip)) {
                audit.logRaw(req.emp(), "auth.lockout", "recover", ip);
            }
            audit.logRaw(req.emp(), "2fa.recover.fail", null, ip);
            throw AuthException.unauthorized("복구 코드가 올바르지 않거나 만료되었습니다");
        }
        // user/cred 존재를 disable 전에 확인 — 실패 시 시드만 폐기돼 사용자가 잠기는 것 방지
        UserRow user = users.findById(userId);
        CredentialRow cred = users.findCredential(userId);
        if (user == null || cred == null) throw AuthException.unauthorized("자격 정보가 유효하지 않습니다");
        limiter.recordSuccess("recover", req.emp());
        // 복구 성공: 기존 시드 즉시 폐기(재등록 강제)
        totpService.disable(userId);
        // 완전 인증 승격 — pending 세션 재사용, 마커 제거 + id 재발급
        HttpSession session = http.getSession(false);
        http.changeSessionId();
        session.removeAttribute(SESSION_2FA_PENDING);
        session.setAttribute(SESSION_USER, userId);
        session.setAttribute(SESSION_CRED, cred.salt());
        audit.logRaw(user.emp(), "2fa.recover.success", null, ip);
        return toMe(user, auth.caps(user));
    }
```

주의: `AuthFilter.ALLOWLIST`는 변경하지 않는다 — recover 경로는 여전히 미인증 통과가 필요(컨트롤러가 pending 세션을 직접 검사, `verify2fa`와 동일 패턴).

- [ ] **Step 4: 전체 테스트 통과 확인**

Run: `cd backend && ./gradlew test`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/worknote/auth/AuthController.java backend/src/test/java/com/worknote/auth/Totp2faRecoverApiTest.java
git commit -m "fix(auth): 복구 코드에 1차 요소 선행 강제 — pending 세션 없인 요청·검증 불가 (MEDIUM)"
```

---

### Task 6: 프런트 복구 UI 보정 — emp 고정 + 12자 코드 입력

**Files:**
- Modify: `frontend/src/login/loginLogic.ts` (normalizeRecoveryCode 추가)
- Modify: `frontend/src/login/LoginPage.tsx` (recovery·recovery-sent 화면)
- Test: `frontend/src/login/loginLogic.test.ts`

**Interfaces:**
- Consumes: 백엔드 규칙(코드 12자 영숫자, normalize = 공백·하이픈 제거 + 대문자)
- Produces: `normalizeRecoveryCode(input: string): string`

- [ ] **Step 1: 실패하는 테스트 작성**

`frontend/src/login/loginLogic.test.ts`에 추가:

```ts
import { normalizeRecoveryCode } from "./loginLogic";

describe("normalizeRecoveryCode", () => {
  it("공백·하이픈 제거 + 대문자화", () => {
    expect(normalizeRecoveryCode(" abcd-2345 efgh ")).toBe("ABCD2345EFGH");
  });
  it("빈 입력은 빈 문자열", () => {
    expect(normalizeRecoveryCode("")).toBe("");
  });
});
```

- [ ] **Step 2: 실패 확인**

Run: `cd frontend && pnpm test`
Expected: FAIL — `normalizeRecoveryCode` export 없음

- [ ] **Step 3: 구현**

`loginLogic.ts`에 추가 (submitRecover 위):

```ts
/** 복구 코드 입력 정규화 — 공백·하이픈 제거 + 대문자화 (백엔드 RecoveryCodec.normalize와 동일 규칙). */
export function normalizeRecoveryCode(input: string): string {
  return input.replace(/[\s-]/g, "").toUpperCase();
}
```

`LoginPage.tsx` 수정 2곳:

1. `mode === "recovery"` 화면의 emp 입력 — 백엔드가 pending 세션의 emp만 허용하므로 읽기 전용으로 고정. 기존 input을 다음으로 교체(기존 className·구조 유지):

```ts
            h("input", { className: "auth-input", value: recoverEmp, readOnly: true,
              title: "복구는 비밀번호 인증에 사용한 계정으로만 진행됩니다" }),
```

안내 문구(`auth-sub` 또는 해당 화면 설명 p)가 "사번을 입력하세요" 취지라면 "비밀번호 인증을 마친 계정의 이메일로 복구 코드를 보냅니다"로 교체.

2. `mode === "recovery-sent"` 화면의 코드 입력 — 8자리 숫자 가정 제거:

```ts
            h("input", { className: "auth-input", value: recoverCode, placeholder: "ABCD2345EFGH", maxLength: 14,
              autoFocus: true, autoComplete: "one-time-code",
              onChange: (e: React.ChangeEvent<HTMLInputElement>) => { setRecoverCode(normalizeRecoveryCode(e.target.value)); setErr(""); } }),
```

(`inputMode: "numeric"` 속성이 있다면 제거 — 영숫자 코드.)
`normalizeRecoveryCode`를 LoginPage import에 추가.

- [ ] **Step 4: 테스트 + 빌드 통과 확인**

Run: `cd frontend && pnpm test && pnpm build`
Expected: 테스트 PASS, 빌드 성공

- [ ] **Step 5: Commit**

```bash
git add frontend/src/login/
git commit -m "fix(login): 복구 화면 보정 — 계정 고정(pending 세션 규칙) + 12자 코드 입력 정규화"
```

---

### Task 7: RedmineUrlValidator + 저장 시점 검증 (MEDIUM §2-2 전반)

**Files:**
- Create: `backend/src/main/java/com/worknote/redmine/RedmineUrlValidator.java`
- Modify: `backend/src/main/java/com/worknote/setting/SettingService.java` (setRedmine)
- Test: `backend/src/test/java/com/worknote/redmine/RedmineUrlValidatorTest.java`

**Interfaces:**
- Consumes: `VaultException.invalid(msg)` (→422), `RedmineException.Upstream(msg)` (→502)
- Produces (Task 8이 사용):
  - `static void validateForSave(String baseUrl)` — 형식·scheme 위반 시 `VaultException.invalid`; DNS 미해석은 허용
  - `static void validateForFetch(String baseUrl)` — 위반·미해석 모두 `RedmineException.Upstream`
  - 차단 대역: loopback / link-local(169.254.0.0/16 — 클라우드 메타데이터 포함) / any-local(0.0.0.0) / multicast. **사설 대역은 허용**(폐쇄망 인트라넷 Redmine이 정상 사용처)

- [ ] **Step 1: 실패하는 테스트 작성**

`backend/src/test/java/com/worknote/redmine/RedmineUrlValidatorTest.java`:

```java
package com.worknote.redmine;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.worknote.vault.VaultException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class RedmineUrlValidatorTest {

    @ParameterizedTest
    @ValueSource(strings = {
        "file:///etc/passwd",
        "gopher://10.0.0.5:6379/_SET",
        "ftp://redmine.intra",
        "not a url",
        "http://",
        "http://user:pw@redmine.intra/",     // userinfo 금지
    })
    void save_rejectsMalformedOrNonHttpSchemes(String url) {
        assertThatThrownBy(() -> RedmineUrlValidator.validateForSave(url))
            .isInstanceOf(VaultException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "http://127.0.0.1:6379",             // loopback
        "http://[::1]:8080",                 // IPv6 loopback
        "http://169.254.169.254/latest/meta-data/",   // link-local(메타데이터)
        "http://0.0.0.0:8080",               // any-local
    })
    void save_rejectsBlockedAddressLiterals(String url) {
        assertThatThrownBy(() -> RedmineUrlValidator.validateForSave(url))
            .isInstanceOf(VaultException.class);
    }

    @Test void save_allowsPrivateRange_closedNetworkRedmine() {
        // 폐쇄망 인트라넷 Redmine이 정상 사용처 — 사설 대역은 허용 (계획 문서 '설계 결정 1' 참조)
        assertThatCode(() -> RedmineUrlValidator.validateForSave("http://10.0.0.5:3000"))
            .doesNotThrowAnyException();
        assertThatCode(() -> RedmineUrlValidator.validateForSave("https://192.168.1.20/redmine"))
            .doesNotThrowAnyException();
    }

    @Test void save_allowsUnresolvableHostname_fetchWillRevalidate() {
        // .invalid TLD(RFC 2606)는 결코 해석되지 않음 — set 시점엔 허용(폐쇄망 DNS 준비 전 설정 가능)
        assertThatCode(() -> RedmineUrlValidator.validateForSave("http://redmine.invalid"))
            .doesNotThrowAnyException();
    }

    @Test void fetch_rejectsUnresolvableHostname() {
        assertThatThrownBy(() -> RedmineUrlValidator.validateForFetch("http://redmine.invalid"))
            .isInstanceOf(RedmineException.Upstream.class);
    }

    @Test void fetch_rejectsBlockedAddress() {
        assertThatThrownBy(() -> RedmineUrlValidator.validateForFetch("http://169.254.169.254/"))
            .isInstanceOf(RedmineException.Upstream.class);
    }

    @Test void fetch_rejectsMalformed() {
        assertThatThrownBy(() -> RedmineUrlValidator.validateForFetch("file:///etc/passwd"))
            .isInstanceOf(RedmineException.Upstream.class);
    }
}
```

- [ ] **Step 2: 실패 확인**

Run: `cd backend && ./gradlew test --tests RedmineUrlValidatorTest`
Expected: 컴파일 실패 — `RedmineUrlValidator` 미존재

- [ ] **Step 3: 구현**

`backend/src/main/java/com/worknote/redmine/RedmineUrlValidator.java`:

```java
package com.worknote.redmine;

import com.worknote.vault.VaultException;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;

/**
 * Redmine base URL SSRF 가드 (CWE-918, 감사 §2-2).
 * - scheme은 http/https만 (file/gopher/jar 차단), userinfo(user@host) 금지
 * - loopback · link-local(169.254.0.0/16 — 클라우드 메타데이터 포함) · any-local(0.0.0.0) · multicast 거부
 * - 사설 대역(10/8 등)은 허용 — 폐쇄망 인트라넷 Redmine이 정상 사용처(차단 시 기능 불능)
 * - DNS 미해석 호스트는 저장 시점엔 허용(폐쇄망 DNS 준비 전 설정 가능), 호출 시점 재검증이 최종 방어
 * - 호출 시점 재검증은 resolve-then-connect라 TOCTOU 잔여 위험이 있으나(자바 HttpClient 제약)
 *   저장 후 DNS 레코드 변경(리바인딩) 시나리오를 실질 차단
 */
public final class RedmineUrlValidator {
    private RedmineUrlValidator() {}

    /** 저장 시점 검증 — 형식·scheme 위반 422. 해석되는 호스트는 차단 대역 검사까지. */
    public static void validateForSave(String baseUrl) {
        URI uri = parse(baseUrl);
        try {
            assertAllowed(InetAddress.getByName(uri.getHost()));
        } catch (UnknownHostException e) {
            // 미해석은 저장 허용 — validateForFetch가 호출 시점에 재검증
        }
    }

    /** 호출 시점 재검증 — 모든 위반을 Upstream(502)으로. 미해석도 여기선 차단. */
    public static void validateForFetch(String baseUrl) {
        URI uri;
        try {
            uri = parse(baseUrl);
        } catch (VaultException e) {
            throw new RedmineException.Upstream("redmine_base_invalid");
        }
        try {
            assertAllowed(InetAddress.getByName(uri.getHost()));
        } catch (UnknownHostException e) {
            throw new RedmineException.Upstream("redmine_base_unresolved");
        } catch (VaultException e) {
            throw new RedmineException.Upstream("redmine_base_blocked");
        }
    }

    private static URI parse(String baseUrl) {
        URI uri;
        try {
            uri = URI.create(baseUrl == null ? "" : baseUrl.trim());
        } catch (IllegalArgumentException e) {
            throw VaultException.invalid("올바른 URL이 아닙니다");
        }
        String scheme = uri.getScheme();
        if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
            throw VaultException.invalid("http 또는 https URL만 허용됩니다");
        }
        if (uri.getUserInfo() != null) {
            throw VaultException.invalid("URL에 인증정보(user@host)를 포함할 수 없습니다");
        }
        if (uri.getHost() == null || uri.getHost().isBlank()) {
            throw VaultException.invalid("호스트가 없는 URL입니다");
        }
        return uri;
    }

    /** 차단 대역 검사 — 위반 시 VaultException.invalid. */
    private static void assertAllowed(InetAddress addr) {
        if (addr.isLoopbackAddress() || addr.isLinkLocalAddress()
            || addr.isAnyLocalAddress() || addr.isMulticastAddress()) {
            throw VaultException.invalid("허용되지 않는 대역의 호스트입니다");
        }
    }
}
```

`SettingService.setRedmine` 수정:

```java
    @Transactional
    public void setRedmine(boolean enabled, String baseUrl) {
        String url = baseUrl == null ? "" : baseUrl.trim();
        if (!url.isEmpty()) {
            com.worknote.redmine.RedmineUrlValidator.validateForSave(url);   // SSRF 가드 (감사 §2-2)
        }
        mapper.put(KEY_REDMINE_ENABLED, enabled ? "1" : "0");
        mapper.put(KEY_REDMINE_BASE_URL, url);
    }
```

- [ ] **Step 4: 전체 테스트 통과 확인**

Run: `cd backend && ./gradlew test`
Expected: BUILD SUCCESSFUL — `RedmineSettingTest`의 `http://redmine.intra`는 미해석 허용 규칙으로 계속 통과. (만약 로컬 DNS가 `.intra`를 차단 대역으로 해석하는 환경이면 테스트 URL을 `http://redmine.invalid`로 교체.)

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/worknote/redmine/RedmineUrlValidator.java backend/src/main/java/com/worknote/setting/SettingService.java backend/src/test/java/com/worknote/redmine/RedmineUrlValidatorTest.java
git commit -m "fix(redmine): base URL SSRF 가드 — scheme·차단대역 검증을 저장 시점에 강제 (MEDIUM CWE-918)"
```

---

### Task 8: RedmineClient 호출 시점 재검증 + 응답 2MB 캡 (§2-2 마감 + Info)

**Files:**
- Modify: `backend/src/main/java/com/worknote/redmine/RedmineClient.java`
- Test: `backend/src/test/java/com/worknote/redmine/RedmineClientGuardTest.java` (신규)

**Interfaces:**
- Consumes: Task 7의 `RedmineUrlValidator.validateForFetch`
- Produces: `RedmineClient.get`이 요청 전 재검증 + 본문 `MAX_BODY_BYTES(2MiB)` 초과 시 `RedmineException.Upstream("redmine_response_too_large")`. 패키지 프라이빗 `static byte[] readCapped(InputStream)`.

- [ ] **Step 1: 실패하는 테스트 작성**

`backend/src/test/java/com/worknote/redmine/RedmineClientGuardTest.java`:

```java
package com.worknote.redmine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import org.junit.jupiter.api.Test;

class RedmineClientGuardTest {

    @Test void get_rejectsBlockedBaseBeforeAnyRequest() {
        RedmineClient client = new RedmineClient(new ObjectMapper());
        // loopback — 요청을 보내기 전에 차단돼야 함 (연결 거부 오류가 아니라 base_blocked)
        assertThatThrownBy(() -> client.fetchCurrentLogin("http://127.0.0.1:1", "tok"))
            .isInstanceOf(RedmineException.Upstream.class)
            .hasMessage("redmine_base_blocked");
    }

    @Test void readCapped_underLimit_returnsAll() throws Exception {
        byte[] data = "{\"ok\":true}".getBytes();
        assertThat(RedmineClient.readCapped(new ByteArrayInputStream(data))).isEqualTo(data);
    }

    @Test void readCapped_overLimit_throws() {
        byte[] big = new byte[RedmineClient.MAX_BODY_BYTES + 1];
        assertThatThrownBy(() -> RedmineClient.readCapped(new ByteArrayInputStream(big)))
            .isInstanceOf(RedmineException.Upstream.class)
            .hasMessage("redmine_response_too_large");
    }
}
```

- [ ] **Step 2: 실패 확인**

Run: `cd backend && ./gradlew test --tests RedmineClientGuardTest`
Expected: 컴파일 실패 — `readCapped`/`MAX_BODY_BYTES` 미존재

- [ ] **Step 3: 구현**

`RedmineClient.java`의 `get`을 다음으로 교체 + 상수·헬퍼 추가:

```java
    /** 응답 본문 상한 — SSRF·오설정 시 대용량 바디로 인한 힙 고갈 방지 (감사 §4 Info). */
    static final int MAX_BODY_BYTES = 2 * 1024 * 1024;

    private JsonNode get(String base, String token, String path) {
        if (base == null || base.isBlank()) throw new RedmineException.Upstream("base_url 미설정");
        RedmineUrlValidator.validateForFetch(base);   // 호출 시점 재검증 — 저장 후 DNS 변경(리바인딩)·직접 DB 조작 대응
        String url = base.replaceAll("/+$", "") + path;
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .header("X-Redmine-API-Key", token)
                .header("Accept", "application/json")
                .GET().build();
            HttpResponse<java.io.InputStream> res =
                http.send(req, HttpResponse.BodyHandlers.ofInputStream());
            byte[] body;
            try (java.io.InputStream in = res.body()) {
                body = readCapped(in);
            }
            int sc = res.statusCode();
            if (sc == 401 || sc == 403) throw new RedmineException.Auth("redmine_token_invalid");
            if (sc == 404) throw new RedmineException.NotFound("redmine_not_found");
            if (sc >= 400) throw new RedmineException.Upstream("redmine_upstream_" + sc);
            return json.readTree(body);
        } catch (RedmineException e) {
            throw e;
        } catch (Exception e) {
            throw new RedmineException.Upstream("redmine_io: " + e.getClass().getSimpleName());
        }
    }

    /** 상한 초과 시 즉시 중단 — 전체를 힙에 올리기 전에 차단. */
    static byte[] readCapped(java.io.InputStream in) throws java.io.IOException {
        byte[] buf = in.readNBytes(MAX_BODY_BYTES + 1);
        if (buf.length > MAX_BODY_BYTES) {
            throw new RedmineException.Upstream("redmine_response_too_large");
        }
        return buf;
    }
```

- [ ] **Step 4: 전체 테스트 통과 확인**

Run: `cd backend && ./gradlew test`
Expected: BUILD SUCCESSFUL — Redmine API 통합테스트들은 전부 `@MockBean RedmineClient`라 영향 없음

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/worknote/redmine/RedmineClient.java backend/src/test/java/com/worknote/redmine/RedmineClientGuardTest.java
git commit -m "fix(redmine): 호출 시점 URL 재검증(DNS 리바인딩 대응) + 응답 2MB 상한"
```

---

### Task 9: SMTP STARTTLS 필수화 + 서버 인증서 검증 (Low)

**Files:**
- Modify: `backend/src/main/java/com/worknote/auth/totp/SmtpMailSender.java`
- Test: `backend/src/test/java/com/worknote/auth/totp/SmtpMailSenderTest.java` (신규)

**Interfaces:**
- Consumes: 없음
- Produces: 패키지 프라이빗 `static Properties mailProperties(String host, int port, boolean starttls, boolean auth)` — starttls=true면 `starttls.required=true` + `ssl.checkserveridentity=true` 동반

- [ ] **Step 1: 실패하는 테스트 작성**

`backend/src/test/java/com/worknote/auth/totp/SmtpMailSenderTest.java`:

```java
package com.worknote.auth.totp;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Properties;
import org.junit.jupiter.api.Test;

class SmtpMailSenderTest {

    @Test void starttlsEnabled_forcesRequiredAndServerIdentityCheck() {
        Properties p = SmtpMailSender.mailProperties("smtp.corp.local", 587, true, true);
        assertThat(p.getProperty("mail.smtp.starttls.enable")).isEqualTo("true");
        assertThat(p.getProperty("mail.smtp.starttls.required")).isEqualTo("true");   // 평문 다운그레이드 차단
        assertThat(p.getProperty("mail.smtp.ssl.checkserveridentity")).isEqualTo("true");
        assertThat(p.getProperty("mail.smtp.auth")).isEqualTo("true");
    }

    @Test void starttlsDisabled_noTlsProps() {
        Properties p = SmtpMailSender.mailProperties("smtp.corp.local", 25, false, false);
        assertThat(p.getProperty("mail.smtp.starttls.enable")).isNull();
        assertThat(p.getProperty("mail.smtp.starttls.required")).isNull();
        assertThat(p.getProperty("mail.smtp.auth")).isEqualTo("false");
    }
}
```

- [ ] **Step 2: 실패 확인**

Run: `cd backend && ./gradlew test --tests SmtpMailSenderTest`
Expected: 컴파일 실패 — `mailProperties` 미존재

- [ ] **Step 3: 구현**

`SmtpMailSender.java` — `send`의 인라인 Properties 조립을 정적 메서드로 추출·강화:

```java
    /**
     * JavaMail 프로퍼티 — starttls=true면 opportunistic이 아닌 필수(required)로 강제하고
     * 서버 인증서 identity를 검증한다. 복구 코드·자격이 메일 본문에 실리므로 평문 다운그레이드 차단 (감사 §4 Low).
     */
    static Properties mailProperties(String host, int port, boolean starttls, boolean auth) {
        Properties props = new Properties();
        props.put("mail.smtp.host", host);
        props.put("mail.smtp.port", String.valueOf(port));
        if (starttls) {
            props.put("mail.smtp.starttls.enable", "true");
            props.put("mail.smtp.starttls.required", "true");
            props.put("mail.smtp.ssl.checkserveridentity", "true");
        }
        props.put("mail.smtp.auth", String.valueOf(auth));
        return props;
    }
```

`send()` 앞부분을 다음으로 교체:

```java
    public void send(String to, String subject, String body) {
        boolean auth = !user.isBlank();
        Properties props = mailProperties(host, port, starttls, auth);
        Session session = auth
            ? Session.getInstance(props, new Authenticator() {
                protected PasswordAuthentication getPasswordAuthentication() {
                    return new PasswordAuthentication(user, password);
                }})
            : Session.getInstance(props);
        // (이하 기존 MimeMessage 조립·발송 코드 그대로)
```

- [ ] **Step 4: 테스트 통과 확인**

Run: `cd backend && ./gradlew test --tests SmtpMailSenderTest`
Expected: 2 tests PASS

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/worknote/auth/totp/SmtpMailSender.java backend/src/test/java/com/worknote/auth/totp/SmtpMailSenderTest.java
git commit -m "fix(mail): STARTTLS 필수화 + 서버 인증서 검증 — 복구코드 평문 다운그레이드 차단"
```

---

### Task 10: 업로드 사전 크기 검사 — 힙 적재 전 차단 (Low)

**Files:**
- Modify: `backend/src/main/java/com/worknote/attachment/AttachmentService.java` (precheck 추가)
- Modify: `backend/src/main/java/com/worknote/attachment/AttachmentController.java` (upload)
- Test: `backend/src/test/java/com/worknote/attachment/AttachmentServiceTest.java`

**Interfaces:**
- Consumes: `SettingService.uploadPolicy()` / `UploadPolicy.check(String, long)` (기존)
- Produces: `AttachmentService.precheck(String filename, long size)` — 정책 위반 시 `VaultException`(422)

- [ ] **Step 1: 실패하는 테스트 작성**

`AttachmentServiceTest.java`에 테스트 추가 (기존 클래스의 서비스 구성 방식을 그대로 따라 — 파일을 먼저 읽고 기존 픽스처에 맞춰 작성):

```java
    @Test
    void precheck_rejectsOversizeWithoutLoadingBytes() {
        // 정책 최대보다 큰 선언 크기 — 바이트 적재 없이 422
        org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> svc.precheck("big.png", Long.MAX_VALUE))
            .isInstanceOf(com.worknote.vault.VaultException.class);
    }

    @Test
    void precheck_allowsWithinPolicy() {
        org.assertj.core.api.Assertions.assertThatCode(() -> svc.precheck("ok.png", 10))
            .doesNotThrowAnyException();
    }
```

(필드명 `svc`는 기존 테스트 클래스의 서비스 필드명에 맞춘다. 허용 확장자에 `png`가 없는 픽스처면 픽스처의 허용 확장자로 파일명을 바꾼다.)

- [ ] **Step 2: 실패 확인**

Run: `cd backend && ./gradlew test --tests AttachmentServiceTest`
Expected: 컴파일 실패 — `precheck` 미존재

- [ ] **Step 3: 구현**

`AttachmentService.java`에 추가:

```java
    /**
     * 바이트 적재 전 선검사 — multipart가 선언한 크기로 정책 위반을 조기 차단(힙 DoS 방지, 감사 §4 Low).
     * store()의 실바이트 검사와 이중 방어.
     */
    public void precheck(String filename, long size) {
        settings.uploadPolicy().check(filename, size);
    }
```

`AttachmentController.upload` — 파일명 검사 뒤, `file.getBytes()` 앞에 삽입:

```java
        svc.precheck(name, file.getSize());   // 힙 적재 전 크기·확장자 선검사
        AttachmentRow row = svc.store(id, name, file.getBytes(), guard.who(user));
```

- [ ] **Step 4: 전체 테스트 통과 확인**

Run: `cd backend && ./gradlew test --tests "com.worknote.attachment.*"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/worknote/attachment/ backend/src/test/java/com/worknote/attachment/
git commit -m "fix(attachment): 업로드 크기 선검사 — 정책 위반 파일의 힙 적재 차단"
```

---

### Task 11: AttachmentService.pathOf 루트 접두 가드 (Low — 심층방어)

**Files:**
- Modify: `backend/src/main/java/com/worknote/attachment/AttachmentService.java` (pathOf)
- Test: `backend/src/test/java/com/worknote/attachment/AttachmentServiceTest.java`

**Interfaces:**
- Consumes: 기존 `pathOf(AttachmentRow)` 시그니처 유지
- Produces: relPath가 루트를 벗어나면 `VaultException.invalid` (read/delete/deleteForNodes 모두 이 경로를 지나므로 일괄 방어)

- [ ] **Step 1: 실패하는 테스트 작성**

`AttachmentServiceTest.java`에 추가 (AttachmentRow 생성자: `id, nodeId, filename, ext, mime, size, relPath, createdBy, createdAt`):

```java
    @Test
    void pathOf_rejectsTraversalOutsideRoot() {
        AttachmentRow evil = new AttachmentRow("att-evil", "n1", "e.png", "png", "image/png",
            1, "../../etc/passwd", "tester", "2026-07-03T00:00:00");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> svc.pathOf(evil))
            .isInstanceOf(com.worknote.vault.VaultException.class);
    }
```

- [ ] **Step 2: 실패 확인**

Run: `cd backend && ./gradlew test --tests AttachmentServiceTest`
Expected: FAIL — 예외 없이 루트 밖 Path 반환

- [ ] **Step 3: 구현**

`pathOf` 교체:

```java
    public Path pathOf(AttachmentRow row) {
        Path p = root.resolve(row.relPath()).normalize();
        if (!p.startsWith(root)) {
            // DB 손상·조작 시 루트 밖 파일 접근 차단 — store()의 쓰기 가드와 대칭 (감사 §4 Low)
            throw VaultException.invalid("잘못된 첨부 경로");
        }
        return p;
    }
```

- [ ] **Step 4: 전체 테스트 통과 확인**

Run: `cd backend && ./gradlew test --tests "com.worknote.attachment.*"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/worknote/attachment/AttachmentService.java backend/src/test/java/com/worknote/attachment/AttachmentServiceTest.java
git commit -m "fix(attachment): pathOf 루트 접두 검증 — 읽기 경로도 store와 대칭 가드"
```

---

### Task 12: 공유 첨부 이미지 한정 (Low — 설계 확정 구현)

**Files:**
- Modify: `backend/src/main/java/com/worknote/attachment/AttachmentController.java` (shareList·shareDownload)
- Test: `backend/src/test/java/com/worknote/attachment/AttachmentApiTest.java` (공유 첨부 테스트가 있는 파일 — 없으면 신규 테스트 추가)

**Interfaces:**
- Consumes: `UploadPolicy.isImage(String ext)` (기존)
- Produces: `/api/share/{token}/attachments`는 이미지 메타만, `/api/share/{token}/attachments/{id}`는 비이미지 404 (`VaultException.notFound` — 무효 케이스와 균등)

- [ ] **Step 1: 실패하는 테스트 작성**

공유 첨부를 다루는 기존 테스트 파일을 찾아(`grep -rn "share.*attachments" backend/src/test`) 같은 픽스처 위에 추가. 기존 픽스처가 없으면 `AttachmentApiTest`의 노드·공유링크 생성 패턴을 따른다:

```java
    @Test
    void shareDownload_nonImage_returns404() throws Exception {
        // (기존 픽스처 재사용) 노드에 txt 첨부 업로드 + 공유 링크 생성
        String attId = uploadAttachment(nodeId, "doc.txt", "text".getBytes());   // 기존 헬퍼/패턴 사용
        String token = createShareToken(nodeId);
        mvc.perform(get("/api/share/" + token + "/attachments/" + attId))
            .andExpect(status().isNotFound());   // 비이미지 — 공유 스코프 반출 금지 (감사 §4)
    }

    @Test
    void shareList_containsOnlyImages() throws Exception {
        uploadAttachment(nodeId, "doc.txt", "text".getBytes());
        uploadAttachment(nodeId, "pic.png", pngBytes());
        String token = createShareToken(nodeId);
        mvc.perform(get("/api/share/" + token + "/attachments"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].filename").value("pic.png"));
    }
```

(`uploadAttachment`/`createShareToken`/`pngBytes`는 기존 테스트의 실제 헬퍼·패턴 명칭으로 치환. txt가 업로드 정책 허용 목록에 없으면 정책을 테스트에서 확장하거나 허용된 비이미지 확장자(pdf 등)를 사용.)

- [ ] **Step 2: 실패 확인**

Run: `cd backend && ./gradlew test --tests "*Attachment*"`
Expected: FAIL — 비이미지가 200으로 서빙됨

- [ ] **Step 3: 구현**

`AttachmentController.shareList` 스트림에 필터 추가:

```java
    /** 공유 노트의 첨부 목록 — 토큰 검증(비증가). 이미지 한정: SharePage 인라인 렌더 전용, 비이미지 반출 통로 차단. */
    @GetMapping("/share/{token}/attachments")
    public List<Map<String, Object>> shareList(@PathVariable String token, HttpServletRequest req) {
        UserRow user = user(req);
        String nodeId = share.nodeIdForAttachment(token, user == null ? null : user.emp());
        return svc.findByNode(nodeId).stream()
            .filter(r -> UploadPolicy.isImage(r.ext()))
            .map(r -> meta(r, "/api/share/" + token + "/attachments/" + r.id()))
            .toList();
    }
```

`shareDownload`의 소속 검사 직후에 추가:

```java
        if (row == null || !row.nodeId().equals(nodeId) || !UploadPolicy.isImage(row.ext())) {
            throw VaultException.notFound("첨부를 찾을 수 없습니다");   // 비이미지도 무효와 균등 404
        }
```

- [ ] **Step 4: 전체 테스트 통과 확인**

Run: `cd backend && ./gradlew test`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/worknote/attachment/AttachmentController.java backend/src/test/java/com/worknote/attachment/
git commit -m "fix(share): 공유 첨부 이미지 한정 — 비이미지 원본의 토큰 스코프 반출 차단"
```

---

### Task 13: 문서 갱신 — 조치 결과 기록 + 운영 체크리스트

**Files:**
- Create: `docs/security/2026-07-03-remediation.md`
- Modify: `docs/operator-guide.md` (§3 최초 설정 체크리스트에 보안 항목 보강)

**Interfaces:**
- Consumes: Task 1~12의 커밋 해시 (`git log --oneline`으로 수집)
- Produces: 감사 지적사항 ↔ 조치 커밋 대응표

- [ ] **Step 1: remediation 문서 작성**

`docs/security/2026-07-03-remediation.md`:

```markdown
# 보안점검 조치 결과 — 2026-07-03 감사 대응

원본: `2026-07-03-security-audit.md`

## 조치 완료

| 감사 항목 | 심각도 | 조치 | 커밋 |
|---|---|---|---|
| §3 SCA 39건 | 심각3·위험16·보통13·일반7 | Spring Boot 3.5.14 업그레이드(+BOM 오버라이드) | <hash> |
| §2-1 인증 시도 제한 부재 | HIGH | AuthRateLimiter — 계정 5회·IP 30회 실패 시 5분 잠금, 429 + auth.lockout 감사. login·2fa/verify·recover/verify 적용 | <hash>, <hash> |
| §2-2 SSRF | MEDIUM | RedmineUrlValidator — 저장·호출 양 시점 검증 + 응답 2MB 캡 | <hash>, <hash> |
| §2-3 복구코드 우회 | MEDIUM | pending 세션(1차 요소) 선행 강제 + 12자 영숫자(59.4bit) 상향 | <hash>, <hash>, <hash> |
| §4 SMTP 평문 다운그레이드 | Low | STARTTLS required + checkserveridentity | <hash> |
| §4 업로드 힙 적재 | Low | 크기 선검사(precheck) | <hash> |
| §4 pathOf 가드 공백 | Low | 루트 접두 검증(store와 대칭) | <hash> |
| §4 공유 첨부 | Low | 이미지 한정 확정 — 비이미지 404, 목록 필터 | <hash> |

## 설계상 감사 권고와 다른 결정

- SSRF: 사설 대역 미차단(폐쇄망 인트라넷 Redmine이 정상 사용처) — loopback·link-local·any-local·multicast만 차단. https 강제 안 함(폐쇄망 HTTP 전제).
- 복구 1차 요소: 비밀번호 재입력 대신 pending 세션 요구(동등 보증 — pending은 비밀번호 검증의 산물).

## 의도적 미조치 (수용한 잔여 위험)

- CSP `unsafe-inline`/`unsafe-eval` — mermaid 요구. 강화 시 nonce 기반 재설계 필요(별도 과제).
- 업로드 매직바이트 미검증 — 서버파생 MIME + nosniff + attachment로 XSS 미유발. 콘텐츠 스푸핑 잔여 갭 수용.
- rate-limit는 인메모리(재기동 시 초기화) — 단일 인스턴스·폐쇄망 전제 수용.
- SSRF 호출 시점 재검증은 resolve-then-connect(TOCTOU 잔여) — 자바 HttpClient 제약, 리바인딩 실질 차단으로 수용.

## 배포 하드닝 체크리스트 (§5 — 운영 설정, 코드 무관)

- [ ] `WORKNOTE_MODE=server` (미설정 시 전 API 무인증)
- [ ] TLS 종단 시 `application.yml` 쿠키 `secure: true` 활성화
- [ ] 시크릿 env 주입: `WORKNOTE_ADMIN_PASSWORD`, `WORKNOTE_2FA_KEY`(Base64 32B), `WORKNOTE_SMTP_*`
- [ ] SMTP 서버가 STARTTLS 지원 시 `WORKNOTE_SMTP_STARTTLS=true`
```

`<hash>`는 `git log --oneline`에서 실제 커밋 해시로 치환.

- [ ] **Step 2: 운영자 가이드 §3 보강**

`docs/operator-guide.md`의 "## 3. 최초 설정 체크리스트" 섹션에 위 배포 하드닝 4항목이 빠져 있으면 추가(이미 있는 항목은 중복 금지 — 파일을 읽고 diff 최소로).

- [ ] **Step 3: Commit**

```bash
git add docs/security/2026-07-03-remediation.md docs/operator-guide.md
git commit -m "docs(security): 감사 조치 결과 기록 + 운영 배포 하드닝 체크리스트"
```

---

## 최종 검증 (전 태스크 완료 후)

- [ ] `cd backend && ./gradlew clean test` — 전체 그린
- [ ] `cd frontend && pnpm test && pnpm build` — 전체 그린 + 빌드 성공
- [ ] `git log --oneline`으로 13개 커밋 확인
