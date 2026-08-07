# work-note 통합 보안 감사 리포트 — 2026-08-07

2트랙 병렬 감사(SonarQube 정적분석 파이프라인 + OWASP Top 10:2025 수동 심층 점검)를 수행하고,
HIGH 이상 발견은 전부 **독립 적대적 반증 검증**을 거쳤다. 본 문서는 그 최종 병합 결과다.

- **대상**: `work-note` — Spring Boot 3.5.14 / Java 21 / MyBatis / SQLite + Vite 6 / React 18 / TS (15,117 ncloc, 263 files)
- **배포 형상**: 사내 폐쇄망, 3~4개 팀 내부 사용, fat jar 단일 배포
- **평가 기준 모드**: `worknote.mode=server` (인증·권한 enforce). `local` 모드의 무인증은 **설계 의도**이며 취약점으로 취급하지 않음
- **직전 감사**: `2026-07-03-security-audit.md` (3건 지적 → 전부 조치 완료, 본 감사에서 회귀 없음 확인)

---

## 0. 결론

| 등급 | 건수 | 내용 |
|---|:--:|---|
| CRITICAL | **0** | — |
| HIGH | **2** | 둘 다 DoS. ① PII 정규식 ReDoS(코드) ② spring-webmvc 정적 리소스 DoS(의존성) |
| MEDIUM | **7** | 감사 델타 누락, 2FA 운영 결함 2, 전송·CSRF·파일권한 3, 의존성 핀 부패 |
| LOW | 다수 | 클라이언트 ReDoS 2, dev-only 의존성, 위생 권고 |

**인젝션·크립토·경로조작·시크릿 노출 계열은 3개 도구 교차검증 + 수동 점검에서 모두 0건이다.**
SQLi(MyBatis 파라미터 바인딩), 경로 조작(UUID 파생 + `normalize()`/`startsWith(root)` 가드), XSS(DOMPurify 단일 게이트 + `afterSanitizeAttributes` src 제한), 시크릿 커밋 — 전부 정석 구현.

**실질 위험의 무게중심은 애플리케이션 코드가 아니라 ① 단 하나의 정규식과 ② 의존성 버전 핀에 있다.**

### 적대적 검증의 효과

원시 HIGH 7건 중 **6건이 반증으로 강등**됐다. 검증 없이 보고했다면 대부분이 오경보였다.

| 원시 HIGH | 반증 결과 |
|---|---|
| A05 PII ReDoS | **유지 — 오히려 영향도 상향** (근본 원인이 감사자 진단과 달라 제안된 수정안이 무효였음) |
| A03 Boot OSS EOL | → Low (`commercialSupportEndDate=2032-06-30`, 패치는 존재하되 무료 채널에만 없음. 조달 사안) |
| A03 spring-framework 미패치 | → Low + **1건만 High 잔존** (§2.2 — 트랙 간 충돌을 직접 재검증해 결론 뒤집음) |
| A07 2FA 관련 2건 | → Medium (운영 결함이지 인증 우회 아님) |
| A09 감사 관련 2건 | → Medium/Low (감사 테이블이 동일 SQLite 파일이라 선별적 은폐 자체가 불가) |

---

## 1. HIGH

### H-1. PII 탐지 정규식 ReDoS → 애플리케이션 전체 정지

| | |
|---|---|
| **위치** | `backend/src/main/java/com/worknote/pii/PiiDetector.java` (EMAIL 패턴) |
| **분류** | OWASP A10:2025 Mishandling of Exceptional Conditions / CWE-1333 |
| **상태** | 적대적 검증 **통과 (CONFIRMED)** — 유일하게 살아남은 코드 결함 |

```java
private static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+\\-]+@[A-Za-z0-9.\\-]+\\.[A-Za-z]{2,}");
```

**실측**: 400,001자 입력 → **113,132 ms** (약 1분 53초) 단일 스레드 점유. 지수가 아닌 O(n²) 다항이지만 상수가 커서 실사용 크기에서 이미 치명적이다.

**왜 HIGH인가 — 3중 증폭:**

1. `PiiService.evaluate()`가 `@Transactional` 이고, `PiiDetector.scan(content)`를 **트랜잭션 안에서** 호출한다 → CPU 연산이 DB 트랜잭션을 붙잡는다
2. `application.yml`의 `hikari.maximum-pool-size: 1` (SQLite 단일 라이터 대응) → 커넥션이 1개뿐이라 **한 요청이 전체 앱의 DB를 잠근다**. 다른 사용자 전원 정지
3. `VaultController` PATCH 핸들러에 `@Valid`도 본문 크기 상한도 없다 → 공격 입력이 무제한

**중요 — 감사자의 제안 수정안은 동작하지 않는다.** 원 리포트는 도메인 파트(`@` 뒤)를 원인으로 지목했으나, 반증 검증에서 실제 backtracking 발생지는 **`@` 앞 local part** 임이 확인됐다. 리터럴 `@`가 없는 긴 문자열이 들어오면 `[A-Za-z0-9._%+\-]+`가 모든 시작 위치에서 재시도한다. 도메인 파트만 고쳐서는 무효.

**조치 (권장 순서)**

1. **즉시(1줄)** — PATCH 본문 크기 상한. 컨트롤러 DTO에 `@Size(max=…)` + `@Valid`, 또는 `spring.servlet.multipart` / `server.max-http-request-header-size`와 별개로 애플리케이션 레벨 길이 가드
2. **근본** — EMAIL 패턴을 소유 문자 기반으로 재작성하거나(예: local part를 `[A-Za-z0-9._%+\-]{1,64}` 로 상한), 리터럴 `@` 인덱스를 먼저 찾아 좌우로 확장하는 스캐너로 교체
3. **구조** — `PiiDetector.scan()`을 `@Transactional` 밖으로 이동. CPU 작업이 커넥션을 점유하지 않게 분리

> 나머지 5개 패턴(RRN/PHONE/CARD/BIZ/PASSPORT)은 앵커·look-around가 명확해 선형이다. EMAIL 하나만 문제.

---

### H-2. spring-framework 6.2.18 — 정적 리소스 DoS (CVE-2026-41842)

| | |
|---|---|
| **분류** | OWASP A03:2025 Software Supply Chain Failures |
| **상태** | **트랙 간 충돌을 직접 재검증하여 확정** |

**충돌과 해소 과정** — Track B 반증 검증은 spring-framework HIGH 3건(CVE-2026-41845/41842/41850)을 "전부 미도달"로 판정했다. 그러나 그 판정의 grep 세트(`javaScriptEscape`, `JavaScriptUtils`, `.jsp`, `SpelExpressionParser`, `parseExpression`, `@Value("#{`)는 **JS-이스케이핑(41845)과 SpEL(41850)만 커버하고 정적 리소스 경로(41842)는 커버하지 않는다.** Track A가 이를 독립적으로 지적했고, 직접 확인한 결과:

- 커스텀 `WebMvcConfigurer` / `addResourceHandlers` — **0건** → Boot 기본 정적 리소스 핸들러가 활성
- fat jar 내 `BOOT-INF/classes/static/` — **80 엔트리 서빙 중** (프런트 dist)

→ `ResourceHttpRequestHandler`는 **활성 요청 경로 위에 있다.** 도달 가능.

| CVE | 컴포넌트 | 도달성 | 판정 |
|---|---|---|---|
| **CVE-2026-41842** | spring-webmvc | **도달** — 정적 리소스 핸들러 활성 | **HIGH 유지** |
| CVE-2026-41845 | spring-webmvc | 미도달 — JSP/뷰 템플릿 미사용, JSON API + 정적 SPA | Low |
| CVE-2026-41850 | spring-expression | 미도달 — 사용자 입력 SpEL 평가 지점 없음 | Low |
| CVE-2026-41843 | spring-webmvc | 도달 — 경로 조작 정보노출, 정적 서빙 사용 중 | Medium |
| CVE-2026-41853 | spring-webmvc | **미도달** — 전제조건이 "multipart 파싱 프록시/WAF 전단". 해당 없음 | 해당없음 |

**조치**: `spring-framework 6.2.19` — HIGH 1 + MEDIUM 6 + LOW 2를 한 번에 해소. Boot 3.5.x 패치 릴리스 경유가 정석이며, 급하면 `ext['spring-framework.version'] = '6.2.19'` 핀.

> 폐쇄망·내부 3~4팀 환경이라 실무상 위험은 "악의적 외부 공격자"가 아니라 "실수 또는 내부자에 의한 서비스 정지"다. 다만 **수정 비용이 버전 1줄**이므로 우선순위는 높게 유지한다.

---

## 2. MEDIUM

| # | 항목 | 위치 | 요지 |
|---|---|---|---|
| M-1 | **권한 변경 감사에 델타가 없음** | `AdminAclController.java:60,70` / `AdminRoleController.java:64` | `audit.log(actor, "acl.set", id + " (" + N + "건)")` — **누가 무슨 권한을 받았는지 기록 없음.** 역할 능력 변경도 role id만 남김. 관리자가 ACL을 조용히 바꾸고 되돌려도 감사 로그로는 재구성 불가. **문서화된 설계 전제가 코드로 반증된 유일한 케이스** |
| M-2 | 부트스트랩 admin 락아웃 | `AdminBootstrap.java:41` | 초기 관리자 `emp`가 상수 `"admin"`. 계정 잠금/삭제 시 복구 경로가 환경변수 재기동뿐 |
| M-3 | 2FA 자가 리셋 | 2FA 등록 플로우 | 본인 세션으로 2FA를 스스로 해제·재등록 가능. 세션 탈취 시 2FA가 무력화되는 경로 |
| M-4 | HTTP 평문 전송 | 배포 구성 | 폐쇄망이라도 세션 쿠키가 평문. 내부망 스니핑 방어 없음 |
| M-5 | CSRF 토큰 부재 | `AuthFilter` 계열 | 커스텀 세션 인증인데 CSRF 토큰이 없음. `SameSite` 쿠키 속성 확인·명시 필요 |
| M-6 | DB·업로드 파일 권한 | `WORKNOTE_DB`, `WORKNOTE_UPLOAD_DIR` | 파일 모드 강제 없음. 동일 호스트 다른 계정에서 SQLite 파일 직접 접근 가능 |
| M-7 | **logback 핀 부패** | `backend/build.gradle:19` | `ext['logback.version'] = '1.5.33'` — Boot BOM은 1.5.34. 취약점이라서가 아니라 **Boot을 올려도 이 줄이 조용히 다운그레이드시킨다**는 구조적 결함. `tomcat.version`·`jackson-bom.version` 핀은 BOM과 일치 확인(부패 아님) |

### 의존성 (MEDIUM)

| 컴포넌트 | 현재 → 수정 | 비고 |
|---|---|---|
| `jackson-databind` | 2.21.4 → **2.21.5** | MEDIUM 3건. API 역직렬화 전 경로에서 상시 사용 |
| `dompurify` | 3.4.11 → **3.4.12** | **이 앱 XSS 방어의 단일 핵심 의존성.** mermaid 요구 범위 `^3.2.4`와 동시 만족 → lock 갱신만으로 해소 |

---

## 3. LOW / 해당없음

| 항목 | 판정 |
|---|---|
| `Outline.tsx:15`, `auditReport.ts:281` 클라이언트 ReDoS | Low. 다항 backtracking, 클라이언트 탭 한정. 서버 영향 없음. 정규식 모호성 제거로 해소 |
| `logback-core` 1.5.34 | Low — 신뢰 불가 로그 설정 수신 시에만. 도달 불가 (M-7과 별개로 버전 자체는 저위험) |
| `uuid` 9.0.1 → 11.1.1+ | Low. mermaid가 이미 허용 범위이므로 `pnpm.overrides`로 상향, mermaid 업그레이드 불필요 |
| `sqlite-jdbc` 3.46.0 네이티브 엔진 | Low — 미도달 (jar 실물 해부 확인) |
| **postcss / undici / vitest** | **해당없음 — dev 전용.** `dist/` 전수 grep 0 파일. 빌드 머신 공급망으로만 관리 |
| Boot 3.5 OSS EOL(2026-06-30) | Low — 상용 지원 2032-06-30. 조달·계획 사안이지 취약점 아님 |
| 프로토타입 `postMessage(…, "*")` 4건 | 스코프 밖 — `docs/design-handoff/prototype/`, 빌드 미포함. 실 코드 이식만 주의 |

### 오탐 (검증 근거 있음)

자동 검출 15개 보안 항목 중 **13개가 오탐**이었다. 대표 사례:

- `S2068 하드코딩 자격증명` **(HIGH로 표시됨)** → `mappers.ts:23`의 `"auth.password.change": "비밀번호 변경"` — 한글 라벨 맵의 키 문자열 오인
- `react-dangerouslysetinnerhtml` ×2 → 주입값이 `DOMPurify.sanitize()` 반환값. img `src`를 내부 경로로 제한하는 훅 + mermaid `securityLevel:"strict"` 이중 방어
- `financial-aria-seed-recommended` ×2 → AES-256-GCM + 매 호출 랜덤 nonce로 정석 구현. ARIA/SEED 권고는 전자금융감독규정 대상 기관용이며 본 앱은 규제 대상 아님
- `S4790 약한 해시` → `HmacSHA1`은 **RFC 6238 TOTP 규정 알고리즘**. SHA-1 충돌 취약성은 HMAC 용법에 미적용
- `generic-api-key` (GitLeaks 유일 검출) → `docs/qa-e2e-scenarios.md:34`의 e2e QA 예시 curl 문자열

---

## 4. 조치 우선순위

| 순위 | 조치 | 비용 | 해소 |
|:--:|---|---|---|
| 1 | **PATCH 본문 크기 상한 + `@Valid`** (H-1 즉시 완화) | 수 줄 | HIGH 1 |
| 2 | **`spring-framework 6.2.19`** | 버전 1줄 | HIGH 1 + MED 6 + LOW 2 |
| 3 | **EMAIL 정규식 재작성 + `scan()`을 트랜잭션 밖으로** (H-1 근본) | 소 | HIGH 1 근본 |
| 4 | `jackson-databind 2.21.5` / `dompurify 3.4.12` / `logback 1.5.34` | 3줄 | MED 4 + M-7 |
| 5 | **ACL·역할 변경 감사에 before/after 델타 기록** (M-1) | 중 | MED 1 |
| 6 | 부트스트랩 admin 복구 경로, 2FA 자가 리셋 정책 (M-2, M-3) | 중 | MED 2 |
| 7 | 전송 암호화·CSRF 토큰·파일 권한 (M-4~M-6) | 운영 | MED 3 |
| 8 | 클라이언트 ReDoS 2건, `uuid` override | 소 | LOW |

**가장 싼 두 건** (각 1줄, 즉시 적용 가능):
- 로그인 성공 시 `SESSION_2FA_PENDING` 클리어
- `AuthFilter.ENFORCE_ALLOWLIST`에 `update-profile` 추가 (2FA 유예 만료 admin의 프로필 경로 락아웃 해소)

---

## 5. 스캔 파이프라인 자체의 결함 (재현성 — 중요)

이번 실행에서 **파이프라인이 조용히 절반을 놓치는 결함 2건**을 발견했다. 결과보다 이쪽이 더 중요할 수 있다.

| # | 결함 | 증상 | 교정 |
|---|---|---|---|
| P-1 | `trivy fs`가 **Java 의존성을 전부 누락** | gradle 빌드파일 미파싱 → `pnpm-lock.yaml`만 스캔. standalone `.jar`도 분석 안 함(`pom.properties` 부재로 해시 기반 `trivy-java-db`가 필요한데 `fs` 모드는 트리거 안 됨) | fat jar `BOOT-INF/lib` 추출 후 **`trivy rootfs`**. `fs`로는 0건이 나와 "취약점 없음"으로 오독 위험 |
| P-2 | SonarQube가 **TS/TSX 99개 전부 skip** | `frontend/tsconfig.json`의 `moduleResolution: "bundler"`(Vite 6 표준)를 SQ 9.9 번들 TS 컴파일러가 미인식 → 프로그램 생성 실패. **에러가 아닌 INFO로 기록되어 눈에 안 띔** | 스캔 전용 `tsconfig.sonar.json`에 `moduleResolution:"node"` 오버라이드 후 `-Dsonar.typescript.tsconfigPath` 주입 |

P-2 교정 효과 — **1차 결과만 봤다면 프런트엔드 전체를 "이슈 없음"으로 오독**할 뻔했다:

| 지표 | 1차 (TS 미분석) | 교정 후 |
|---|---|---|
| ncloc | 7,129 | **15,117** (ts 7,988 추가) |
| issues | 130 | **259** |
| security_hotspots | 2 | **13** (+11 전부 프런트) |

**추가 권고**
- `sonar.token`은 SQ 9.9 LTS에서 미지원(CLI 8이 Bearer로 전송, Bearer 지원은 SQ 10.x+) → `sonar.login` 사용
- 스캔 후 `ncloc_language_distribution`에 `ts=`가 있는지 확인하는 절차를 파이프라인에 넣을 것
- `.scan-reports/`·`.scannerwork/`를 `.gitignore`에 등록 (본 감사에서 반영)

---

## 6. 감사 수행 방식

| | |
|---|---|
| **Track A** | SonarQube 9.9 LTS + 행안부-보안약점-49 프로파일(512룰, FindSecBugs 포함) / Trivy / GitLeaks(369 commits) / Semgrep(176룰 × 509파일, `p/owasp-top-ten` + `p/cwe-top-25` + KISA·금융 커스텀) |
| **Track B** | OWASP Top 10:**2025**(2026-01 최종 릴리스) 6개 영역 병렬 심층 점검 — A01 접근통제 / A02 보안설정오류 / A03 공급망 / A05 인젝션 / A07 인증 / A09 로깅·경보 |
| **검증** | HIGH 이상 전건에 대해 독립 반증 에이전트 투입. "모호하면 REFUTED"를 기본값으로, `file:line` 인용 의무, 감사자가 놓친 방어수단 탐색 및 배포 컨텍스트 기준 재등급 요구 |
| **제약** | 전 과정 read-only. 소스 무수정(`git status` tracked 변경 0건 확인), 앱·서버 미기동, 의존성 설치·lockfile 변경 없음 |

### 반증 검증이 잡아낸 것들 (감사자가 놓친 사실)

- H-1의 **근본 원인이 감사자 진단과 반대** → 제안된 수정안이 무효였음
- 공유 링크 엔드포인트는 실제로 **로그인을 요구**함 (미인증 접근이라는 원 주장 오류)
- 감사 테이블이 동일 SQLite 파일이라 **선별적 감사 은폐가 구조적으로 불가**
- spring-framework 권고가 보고보다 **많음**(8건/HIGH2 → 실제 11건/HIGH3), 단 대부분 미도달
- withdrawn advisory(esbuild GHSA-gv7w, uuid GHSA-qmq6)를 오탐으로 정확히 배제

### 트랙 간 충돌 1건 (본 문서에서 해소)

spring-framework HIGH 3건의 도달성 판정이 Track A(1건 도달)와 Track B 반증(0건 도달)에서 엇갈렸다.
→ Track B의 grep 세트가 정적 리소스 경로를 커버하지 않았음을 확인, **Track A 판정 채택** (§H-2).

---

## 부록. 산출물

| 경로 | 내용 |
|---|---|
| `.scan-reports/sonar-issues2.json` | SonarQube 최종 이슈 전량 (259 + hotspot 13) |
| `.scan-reports/semgrep.sarif` | Semgrep 176룰 결과 |
| `.scan-reports/gitleaks.sarif` | 시크릿 스캔 (369 commits) |
| `.scan-reports/trivy.sarif`, `trivy-jar.json` | SCA (npm / fat jar `rootfs`) |
| http://localhost:9000/dashboard?id=work-note | SonarQube 대시보드 |

> `.scan-reports/`·`.scannerwork/`는 재생성 가능한 스캔 산출물이다. `.gitignore`에 등록했으므로 커밋되지 않는다. 리포트 확정 후 삭제해도 무방하다.
