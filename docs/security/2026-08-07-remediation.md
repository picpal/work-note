# 2026-08-07 보안감사 조치 기록 — 우선순위 1~4

대상 감사: [`2026-08-07-security-audit.md`](2026-08-07-security-audit.md)
조치 범위: 우선순위 **1~4** (HIGH 2건 + 의존성 MEDIUM 전량). 5~8은 미조치.

## 결과 요약

| 조치 | 대상 | 검증 |
|:--:|---|---|
| 1 | PATCH/POST 본문·이름·태그 상한 + `@Valid` | 신규 테스트 7건 |
| 2 | spring-framework 6.2.18 → **6.2.19** | fat jar 재스캔 |
| 3 | EMAIL 정규식 선형화 + 스캔을 트랜잭션 밖으로 | 400,001자 **113초 → 18ms** |
| 4 | jackson 2.21.5 / logback 1.5.34 / dompurify 3.4.13 | fat jar·lock 재스캔 |

**의존성 취약점: 백엔드 15건 → 0건, 프런트 2건 → 1건**(잔여 `uuid`는 조치 6, 범위 밖).
테스트: 백엔드 **491 passed / 0 failed**, 프런트 **343 passed**.

---

## 조치 1 — 저장 경로 입력 상한 (H-1 심층 방어)

`VaultController.update`에 `@Valid`가 없어 PATCH 본문이 무제한이었다. 상한을 `NodeLimits` 단일 출처로 두고 두 DTO에 적용.

| 항목 | 상한 |
|---|---|
| 본문 | 1,000,000자 (≈ 마크다운 1MB. 이미지는 첨부라 인라인되지 않음) |
| 이름 | 200자 |
| 태그 | 50개 × 각 50자 |
| id / parentId | 100자 |

- 신규 `backend/.../vault/dto/NodeLimits.java`
- `UpdateNodeRequest` / `CreateNodeRequest`에 `@Size`, `VaultController.update`에 `@Valid`
- 초과 시 400 + `{"error": "본문이 너무 깁니다"}` (기존 `MethodArgumentNotValidException` 핸들러 경유)
- 신규 `NodeLimitsApiTest` 7건 — 본문/이름/태그개수/태그길이 초과 거부 + 상한 이내 허용 + create 경로 2건

## 조치 3 — ReDoS 근본 수정 (H-1)

### (a) 정규식 선형화

```diff
-Pattern.compile("[A-Za-z0-9._%+\\-]+@[A-Za-z0-9.\\-]+\\.[A-Za-z]{2,}")
+Pattern.compile("(?<![A-Za-z0-9._%+\\-])[A-Za-z0-9._%+\\-]+@[A-Za-z0-9.\\-]+\\.[A-Za-z]{2,}")
```

선두 경계 lookbehind 하나가 전부다. 없으면 `@`가 없는 긴 문자열에서 **시작 위치마다 로컬파트를 다시 훑어** O(n²)가 된다.

**의미 변화 없음**: 로컬파트는 `@` 직전까지 연속이므로, 런 중간에서 시작하는 매치는 런 선두에서 시작하는 매치와 항상 같은 `@`·같은 도메인을 본다 → 결과 동일. 매치 범위가 바뀌지 않으므로 `exempt_hashes`(승인된 PII 값 해시)도 그대로 유효하다.

> 감사 원본이 제안한 "도메인 파트 수정"은 무효였다. 반증 검증이 실제 발생지를 `@` **앞** 로컬파트로 특정했고, 이 수정은 그에 따른 것이다.
> 나머지 6개 패턴(RRN/PHONE/CARD/BIZ/PASSPORT/DRIVER)은 이미 `(?<!\d)` 계열 경계를 갖고 있었다 — EMAIL만 빠져 있었다.

**실측** (동일 머신):

| 입력 | 조치 전 | 조치 후 |
|---|---|---|
| 400,001자 | 113초 (환산 ~441초) | **18 ms** |
| 40,000자 | 4,419 ms | **0 ms** |

### (b) 스캔을 트랜잭션 밖으로

`PiiService.evaluate`가 `@Transactional`이라 CPU 스캔이 트랜잭션 안에서 돌았다. `hikari.maximum-pool-size: 1`(SQLite 단일 라이터)이므로 **그 요청 하나가 앱 전체의 유일한 커넥션을 점유**했다.

- 신규 `PiiFlagStore`(package-private `@Component`) — 상태 기계 DB 반영 + `@Transactional`
- `PiiService.evaluate`는 **비-트랜잭션**: 스캔 후 `flags.apply(...)` 호출
- 별도 빈이어야 하는 이유: 같은 클래스 안에서 `@Transactional` 메서드를 자기호출하면 프록시를 우회해 트랜잭션이 조용히 사라진다
- 공개 시그니처 `evaluate(String, String)` 불변 → 기존 호출부·테스트 무변경
- 신규 `PiiTransactionBoundaryTest` — 경계가 되돌아가는 것을 리플렉션으로 고정(컴파일 타임에 막을 방법이 없음)

## 조치 2·4 — 의존성

### 접근: 수동 핀을 늘리지 않고 BOM을 올린다

M-7(logback 핀 부패)의 원인은 **수동 핀이 BOM보다 우선**한다는 점이었다. 여기에 `spring-framework` 핀을 추가하면 같은 함정을 하나 더 만드는 셈이라, Boot BOM 자체를 올렸다.

Boot **3.5.16** BOM 확인값 (`spring-boot-dependencies-3.5.16.pom`):

```
spring-framework.version = 6.2.19   ← 필요값과 일치
logback.version         = 1.5.34   ← 핀(1.5.33)이 막고 있던 값
tomcat.version          = 10.1.55  ← 기존 핀과 동일 (핀이 무의미)
assertj.version         = 3.27.7   ← 기존 핀과 동일 (핀이 무의미)
jackson-bom.version     = 2.21.4   ← 2.21.5 필요 → 핀 유지·상향
```

```diff
-id 'org.springframework.boot' version '3.5.14'
+id 'org.springframework.boot' version '3.5.16'

-ext['assertj.version'] = '3.27.7'
-ext['tomcat.version'] = '10.1.55'
-ext['jackson-bom.version'] = '2.21.4'
-ext['logback.version'] = '1.5.33'
+ext['jackson-bom.version'] = '2.21.5'
```

무의미하거나 해로운 핀 3개를 제거해 **BOM보다 높여야 하는 것만 남겼다**. 다음 Boot 업그레이드 때 같은 부패가 재발하지 않는다.

### 검증 — 실제 산출물 재스캔

`trivy rootfs`로 재빌드한 fat jar의 `BOOT-INF/lib` 52개 중첩 jar를 스캔:

```
Results: Target=Java Class=lang-pkgs Type=jar  패키지=61  취약점=0
  spring-core / spring-web / spring-webmvc / spring-expression  6.2.19
  jackson-databind 2.21.5 · logback-core 1.5.34 · tomcat-embed-core 10.1.55
```

> 0건이 **오독이 아님**을 확인했다 — 감사 리포트 §5의 P-1(파이프라인이 Java 의존성을 통째로 놓쳐 0건을 내는 함정)과 같은 모양이라, 패키지 61개가 실제 분석됐고 대상 버전이 반영됐음을 함께 확인했다.

### 프런트 — dompurify

`^3.4.11` → **3.4.13** (GHSA-c2j3-45gr-mqc4는 3.4.12에서 수정, 동일 마이너의 최신 패치 채택).
`pnpm-workspace.yaml`의 overrides 플로어도 `>=3.4.13`으로 상향 — mermaid가 번들한 사본까지 함께 올리는 기존 하드닝 의도 유지. mermaid 10.9.6의 요구 범위 `^3.2.4`와 동시 만족해 **단일 사본**으로 해결됐다.

재스캔: `pnpm-lock.yaml` 139 패키지 → dompurify 소거, 잔여 `uuid 9.0.1`(MEDIUM) 1건.

---

## 미조치 (범위 밖)

우선순위 5~8은 이번 조치에 포함하지 않았다.

| # | 항목 |
|:--:|---|
| 5 | M-1 ACL·역할 변경 감사 델타 기록 |
| 6 | M-2 부트스트랩 admin 복구 경로 / M-3 2FA 자가 리셋 |
| 7 | M-4~M-6 전송 암호화·CSRF 토큰·파일 권한 |
| 8 | 클라이언트 ReDoS 2건(`Outline.tsx`, `auditReport.ts`), `uuid` override |

### uuid 관련 — 감사 권고와 기존 결정의 충돌

감사 리포트는 "mermaid 요구 범위가 uuid 11.1.1+를 이미 허용하므로 `pnpm.overrides`로 상향하면 끝"이라고 했으나, `pnpm-workspace.yaml`에 **반대 취지의 기존 결정**이 주석으로 남아 있다:

> `uuid는 mermaid 10.x가 v9 API에 의존 → v11 강제 시 깨질 위험 + buf 미전달로 비익스플로잇 → 미오버라이드`

semver 범위가 허용하는 것과 런타임 API 호환은 다른 문제다. 조치 6을 실제로 진행할 때는 **mermaid 렌더 경로 실동작 확인이 선행**되어야 하며, 감사 권고를 그대로 적용하면 안 된다.

---

## 변경 파일

**신규(4)** — `pii/PiiFlagStore.java`, `vault/dto/NodeLimits.java`, `pii/PiiTransactionBoundaryTest.java`, `vault/NodeLimitsApiTest.java`
**수정(9)** — `build.gradle`, `pii/PiiDetector.java`, `pii/PiiService.java`, `vault/VaultController.java`, `vault/dto/{Create,Update}NodeRequest.java`, `pii/PiiDetectorTest.java`, `frontend/{package.json,pnpm-workspace.yaml,pnpm-lock.yaml}`
**부수** — `.gitignore`에 `.scan-reports/`·`.scannerwork/` 추가(스캔 산출물 커밋 방지)
