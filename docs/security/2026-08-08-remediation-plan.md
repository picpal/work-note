# 2026-08-07 보안감사 조치 계획 v2 — 우선순위 5~8

대상 감사: [`2026-08-07-security-audit.md`](2026-08-07-security-audit.md)
선행 조치: [`2026-08-07-remediation.md`](2026-08-07-remediation.md) (우선순위 1~4 완료)

**v2 = Codex 독립 리뷰(2026-08-08) 반영판.** 지적 11건 중 9건 수용·2건 부분 반박. v1 대비 변경점은 §2에 정리.

**확정 범위: B0 + B1 + TLS 앱측 준비.** B2(admin 복구)는 연기.

---

## 1. 감사 원문 정정

계획 수립 중 코드를 직접 확인해 리포트와 다른 판정 3건이 나왔다.

| 항목 | 리포트 판정 | 실제 코드 | 조정 |
|---|---|---|---|
| M-3 2FA 자가 리셋 | "본인 세션으로 해제 가능" | `Me2faController.java:82` — **admin은 이미 403 차단** | MEDIUM → **LOW** (비-admin 한정) |
| M-2 admin 락아웃 | "복구 경로가 환경변수 재기동뿐" | `AdminBootstrap.java:30` `countUsers()>0`이면 즉시 return → **지원되는 복구 경로 없음** | 표현 정정: "복구 불가"가 아니라 **"지원되는 복구 절차 부재"**. 호스트 운영자는 sqlite3로 직접 복구 가능 |
| M-5 CSRF | "SameSite 확인 필요" | `application.yml:23` Lax 이미 적용됨 | 배포 환경에 종속 → §3 T5 |

### 배포 환경 확인 결과 (2026-08-08, 사용자 확인)

| 사실 | 영향 |
|---|---|
| 사내 **내부 CA 발급 체계 있음** | M-4 인증서 조달 병목 없음 → **앱측 지원을 이번 범위에 포함**(§4) |
| 다른 사내 앱이 **`xxx.domain.co.kr` 형제 서브도메인** | **M-5 승격.** 등록가능 도메인(`domain.co.kr`)이 같아 형제 서브도메인은 **same-site** — `SameSite=Lax`가 막지 못한다. 세션 쿠키는 host-only라 *읽지는* 못하지만 *실려 나가지는* 진다 |

---

## 2. Codex 리뷰 반영 (v1 → v2 변경점)

| Codex 지적 | 판정 | v2 반영 |
|---|:--:|---|
| 변경과 감사 기록이 비원자적 (`AuditService.java:22` 주석이 명시) | 수용 | **T7-a 신설** — ACL/역할/public 변경은 mutation+델타+audit을 **한 트랜잭션**으로 |
| 4KB 절단이 증거 인멸 프리미티브 (`SetAclRequest`에 컬렉션 상한 없음) | 수용 | **절단 폐기.** 엔트리 개수 상한(`@Size`)으로 델타가 항상 온전히 들어가게 |
| TLS 연기가 잘못된 우선순위 | 부분 | **B3-TLS 앱측을 범위에 편입**(T12). 인증서 발급·배포는 운영 단계 |
| Origin 검증이 ALLOWLIST 뒤면 로그인·2FA·복구를 못 막음 (`AuthFilter.java:65`) | 수용 | **T5 순서 확정** — ALLOWLIST보다 **먼저**. `Origin: null` 거부 |
| 상태변경 GET 누락 — `GET /api/share/{token}`이 조회수 증가 | 수용 | **T13 신설** |
| 마지막 열람 후 첨부 404 (`validate()`를 `nodeIdForAttachment`도 탐) | 수용 | **T13에 포함** — 실제 기능 버그 |
| 비밀번호 변경 경로 제외는 잘못 | 수용 | **T2에 `change-password` 추가** |
| `Outline.tsx` 재작성이 동치 아님 (`## `, `## ###`, `## C#`) | 수용 | **T4에 특성화 테스트 선작성 의무** |
| 파일 권한이 불완전 (WAL/SHM, 생성 시점) | 수용 | **T3 재설계** — 디렉토리 700 중심 + 생성 시 POSIX 속성 |
| `MAX_ENTRIES`가 실제 상한이 아님 | 수용 | **T14 신설** |
| "복구 불가"는 과장 | 부분 | §1 표현 정정. B2 연기는 유지(Codex도 "보안 필수 아님"이라 판단) |

**Codex가 확인해준 v1의 정확한 판단**: `Me2faController` admin 2FA 해제 차단 · `AclAdminService.replace()`가 `@Transactional`이고 프로덕션 호출부 1곳 · `ALTER TABLE audit_log ADD COLUMN` 저위험(`V9__totp_2fa.sql:23` 선례) · JSON1 불필요(월간 리포트가 이미 TS에서 집계) · `auditReport.ts` 정규식 재작성은 진짜 동치.

---

## 3. B0 — 즉시 (순수 코드·저위험)

### T1. 로그인 성공 시 2FA pending 잔류 제거

`auth/AuthController.java:110`. `changeSessionId()`는 id만 바꾸고 내용을 유지하므로(`:92`), 같은 세션에서 2FA 계정으로 부분 인증(`:106`)한 뒤 다른 계정으로 로그인하면 pending 플래그가 남는다.

```java
session.setAttribute(SESSION_CRED, result.credSalt());
session.removeAttribute(SESSION_2FA_PENDING);   // 이전 부분 인증 잔류 제거
```

부분 인증 진입 시 `SESSION_CRED`도 함께 제거해 로그인 전이마다 인증 상태가 명시적으로 초기화되게 한다.

> **2FA 우회는 아니다.** 잔류 시 `AuthFilter.java:82`가 세션을 오히려 차단하고, `verify2fa`는 현재 `SESSION_USER` 기준으로 동작한다. 가용성 결함이므로 과장하지 않는다.

**테스트**: 부분 인증 → 동일 세션 비-2FA 로그인 → 정상 통과 + `/2fa/verify` 거부.

### T2. 유예 만료 admin의 자기관리 경로

`auth/AuthFilter.java:37-40` `ENFORCE_ALLOWLIST`에 `/api/auth/update-profile`, `/api/auth/change-password` 추가.

- `update-profile`은 본인 이름·이메일만 변경(`AuthController.java:263`)하고, **이메일은 TOTP setup의 선행 조건**(`Me2faController.java:41`)이라 허용이 필수
- `change-password`는 현재 비밀번호를 검증하며(`AuthController.java:247`) 등록 게이트를 우회하지 않는다. 제외하면 **유예 만료 admin이 탈취 의심 비밀번호를 못 바꾸는** 상태가 된다 (v1의 판단을 뒤집음)

**테스트**: 유예 만료 admin → 두 경로 200, 그 외 경로 403.

### T3. M-6 — DB·업로드 권한 (v2 재설계)

파일 단위 chmod는 SQLite의 `-wal`/`-shm`/journal을 놓치고, DB 파일은 Flyway/DataSource가 러너보다 먼저 만든다. **디렉토리가 실제 통제 지점이다.**

- DB 파일의 **부모 디렉토리**와 업로드 루트를 `700`으로 (없으면 그 권한으로 생성)
- 첨부는 **생성 시점에** POSIX `600` 속성 부여 — 넓게 만들고 나중에 chmod 하지 않는다 (`AttachmentService.java:54`의 `CREATE_NEW`에 속성 추가)
- 기존 DB 파일이 이미 있으면 `600`으로 보정
- **POSIX 미지원(Windows)이면 skip + WARN** — 예외로 기동을 막지 않는다
- server 모드에서 권한이 부적합한데 보정도 실패하면 **WARN이 아니라 기동 실패**

**테스트**: 임시 디렉토리 기동 → `Files.getPosixFilePermissions` 검증. Windows는 `assumeTrue` skip.

### T4. 항목 8 — 클라이언트 ReDoS 2건

**`auditReport.ts:281`** — Codex가 동치성을 확인했다. 그대로 적용.

```
/^\|[\s:|-]+\|?\s*$/  →  /^\|[\s:|-]+$/
```

**`Outline.tsx:15`** — v1의 제안은 **동치가 아니다**. 현재 동작(Codex 실측):

| 입력 | 현재 결과 |
|---|---|
| `## foo # bar #` | `foo # bar` |
| `## ###` | `#` |
| `##   ` | 빈 헤딩 |
| `## ` | **헤딩 아님** |
| `## C#` | `C` ← GFM 위반 |
| `## C# #` | `C#` |

`(.*)`는 `## `를 매치해버려 동작이 바뀐다.

**작업 순서(엄수)**: ① 위 6케이스를 **특성화 테스트로 먼저 작성**해 현재 동작을 고정 → ② 정규식 제거·선형 구현으로 교체 → ③ `## C#` 케이스만 GFM 정답(`C#`)으로 **의도적 변경**하고 테스트·주석에 명시. 나머지 5케이스는 불변.

**테스트**: 위 6케이스 + 병리 입력 시간 상한.

> **실측으로 정정(2026-08-08)** — 계획 초안이 지정한 병리 입력 `'#' + ' ' + 'a'.repeat(50_000)`은 **폭발하지 않는다**(0.27ms). `.+?`가 한 글자씩 늘어도 꼬리 검사가 `a`에서 즉시 실패해 선형이다. 실제 트리거는 **후행 공백 런** — `.+?`와 `\s*#*\s*`가 같은 공백을 두고 경쟁한다.
>
> | 입력 | 수정 전 | 수정 후 |
> |---|---:|---:|
> | `'## 제목' + ' '×1000 + '끝'` | 197 ms | — |
> | `'## 제목' + ' '×2000 + '끝'` | 1,590 ms | — |
> | `'## 제목' + ' '×4000 + '끝'` | **12,234 ms** | 0.035 ms |
>
> 증가가 제곱이 아니라 **세제곱에 가깝다**(`\s* #* \s*` 3분할이 lazy 그룹과 복합). 감사 리포트의 "Low·다항 backtracking" 판정은 **과소평가**였다 — 합성 입력이 아니라 공백이 붙은 평범한 제목 줄에서 탭이 12초 멈춘다. 서버 영향이 없다는 점만 유효하다.
>
> 구/신 구현 차등 퍼징 20만 줄: 불일치는 의도한 `C#` 형태 703건뿐, 설명되지 않는 divergence 0건.
>
> **남긴 것**: `## ###` → `#`는 GFM상 빈 텍스트가 정답이지만(닫는 시퀀스로 해석), 기존 `.+?`의 최소 1글자 규칙이 만든 인공물이라 **현행 유지**하고 주석으로 표시했다. `## C#`과 달리 사용자 가시 피해가 없어 이번 변경에 섞지 않았다.

### T5. M-5 — Origin 헤더 검증 (v2 순서 확정)

CSRF 토큰을 도입하지 않는다. 단일 오리진 앱이라 Origin 검증으로 충분하고, 토큰은 프런트 변경·저장·수명 관리를 모두 유발한다.

**배치 순서가 핵심이다.** `AuthFilter.java:65`의 `ALLOWLIST`는 최상단에서 return하므로, Origin 검증이 그 뒤에 있으면 **로그인·가입·2FA 검증·복구가 전부 미검증**으로 남는다(= 강제 로그인 CSRF).

- 검증을 `doFilterInternal` **최상단**, `ALLOWLIST` 검사보다 **먼저** 배치
- 대상: POST/PUT/PATCH/DELETE
- `Origin` 있으면 요청 오리진(스킴+호스트+포트)과 정확히 일치해야 함. **`Origin: null` 거부**
- `Origin` 없으면 `Referer` 오리진으로 폴백
- 둘 다 없으면 통과 — 단, **세션 쿠키가 실려 있으면 거부**(브라우저는 cross-origin 상태변경에 Origin을 반드시 붙인다. 쿠키가 있는데 둘 다 없는 건 브라우저 요청이 아니다)
- 비교 기준은 요청 자체에서 유도(배포마다 호스트·포트가 다름). `worknote.canonical-origin` 설정이 있으면 그것을 우선
- `local` 모드는 무인증이므로 미적용

**테스트**: MockMvc — (a) 헤더 없음+쿠키 없음 통과, (b) 헤더 없음+세션 쿠키 거부, (c) 동일 오리진 통과, (d) `https://evil.domain.co.kr` 403, (e) `Origin: null` 403, (f) GET 미검증, (g) **ALLOWLIST 경로(`/api/auth/login`)도 검증됨**, (h) multipart 업로드.

---

## 4. T12 — M-4 TLS 앱측 준비 (Codex P1 반영, 신규)

인증서 발급은 사내 CA 프로세스라 코드로 끝낼 수 없다. **앱이 인증서만 꽂으면 되는 상태**까지 만든다.

- `application.yml`에 `server.ssl.*` 블록을 **환경변수 스위치**로 추가 (`WORKNOTE_TLS_KEYSTORE` 미지정 시 비활성 → 기존 HTTP 동작 불변)
- 세션 쿠키 `secure`를 TLS 활성 여부에 **연동**(하드코딩 금지 — `true` 고정 시 HTTP 배포에서 쿠키가 안 실려 전면 장애)
- 운영 가이드에 내부 CA CSR → PKCS12 변환 → 배포 → 갱신 절차 추가
- HTTP 평문 포트를 열어둘지는 **운영 선택**으로 남기고 기본은 단일 포트

> 이번 범위에서 인증서를 만들지 않는다. 코드·설정·문서만 준비하고, 실제 전환은 인증서 수령 후 별건.

---

## 5. B1 — M-1 감사 델타

**5~8 중 유일한 실질 보안 개선.** 현재 `AdminAclController.java:60`은 `acl.set` + `id + " (3건)"`만 남긴다 — 누가 무슨 권한을 받았는지 기록이 없어, 관리자가 ACL을 바꿨다 되돌리면 감사 로그로 재구성이 불가능하다.

### T6. `audit_log.detail` 컬럼

`V12__audit_detail.sql` — `ALTER TABLE audit_log ADD COLUMN detail TEXT`.

기존 `target`에 JSON을 우겨넣지 않는다. `target`은 감사 화면과 `auditReport.ts` 집계가 이미 사람이 읽는 라벨로 소비한다.

`AuditService`에 detail 인자 오버로드를 추가하고 기존 시그니처는 `detail=null`로 위임 — 호출부 수십 곳을 건드리지 않는다.

### T7-a. 변경과 감사 기록의 원자성 (Codex P1, 신규 — T7보다 먼저)

`AuditService.java:22`가 "감사 insert 단독 실패 시 본 작업은 이미 커밋됨"을 의식적 트레이드오프로 명시한다. 델타를 아무리 정확히 만들어도 **감사 행 자체가 없을 수 있으면 M-1의 목적이 무너진다.**

- **ACL·역할·public 변경 3경로에 한해** mutation + 델타 생성 + audit insert를 **하나의 `@Transactional` 서비스 메서드**로 묶는다
- 감사 기록 실패 = 변경 롤백 (fail-closed)
- 나머지 감사 호출부의 기존 정책(fail-open)은 **건드리지 않는다** — 전역 정책 변경은 범위 밖이고, 권한 변경만이 사후 재구성이 필요한 경로다
- **테스트**: audit insert를 강제 실패시켜 ACL/역할/public 변경이 롤백되는지 검증

### T7. ACL 델타

`admin/AclAdminService.java` `replace()` — `acl.deleteAclForNode(nodeId)` **직전에** 기존 행을 읽어 diff.

```json
{"added":[{"p":"team:t-dev","g":"read"}],
 "removed":[{"p":"user:u-1","g":"edit"}],
 "changed":[{"p":"team:t-qa","from":"read","to":"deny"}]}
```

> grant 값은 `read|edit|deny`다(`AclEntryRequest.java:10`) — 초안의 `write`는 오기.
> 빈 갈래는 키 생략, 전부 무변화면 `detail=null`(빈 토글 방지), 주체키·cap은 정렬해 같은 변경이 항상 같은 JSON이 되게 한다(재구성·diff 용이).

**절단하지 않는다** (v1에서 뒤집음). Codex 지적대로 크기 기반 절단은 "큰 변경을 만들면 증거가 사라지는" 인멸 경로가 된다. 대신 **입력을 제한**한다:

- `SetAclRequest.entries`에 `@Size(max = ...)` — 현재 컬렉션 상한이 아예 없다(`SetAclRequest.java:8`)
- 상한 이내면 델타는 항상 온전히 저장된다
- 상한 초과는 400으로 거부 — 변경도 안 되고 기록 누락도 없다

`replace()`의 반환 타입을 레코드로 변경(`ReplaceResult(String warning, String detail)`). 프로덕션 호출부는 컨트롤러 1곳(Codex 확인).

### T8. 역할 능력 델타

`admin/AdminRoleController.java:64` `role.update` — `RoleAdminService.update`가 갱신 전 name/caps를 읽어 반환.

```json
{"name":{"from":"검토자","to":"리뷰어"},
 "caps":{"added":["res.delete"],"removed":[]}}
```

### T9. public 노출 델타

`AdminAclController.java:70,79` — 이전 모드를 함께 기록(`{"from":null,"to":"public"}` / `{"from":"exclude","to":null}`). 모드값은 `public|exclude`다(`PublicRequest.java:5`) — 초안의 `cascade`는 오기.

공개 노출은 되돌려도 흔적이 남아야 하므로, `public.*`만은 **무변화 재설정도 기록**한다(나머지는 무변화 시 `detail=null`).

### T10. 프런트 표시

- `frontend/src/admin/api.ts:14` `ApiAudit`에 `detail: string | null`
- `screens/Audit.tsx` — detail 있는 행만 펼치기 토글. 렌더(`+ team:t-dev read` / `− user:u-1 write` / `~ team:t-qa read→deny`)
- 렌더 변환은 **순수 함수로 분리**(`auditDetail.ts`) 후 vitest — 프로젝트 테스트 관례(React 렌더 테스트 없음)

### T11. 월간 리포트

`auditReport.ts` 집계에 델타를 **넣지 않는다**. 월간 리포트는 건수 추이용, 델타는 개별 추적용으로 목적이 다르다. `detail` 필드 추가로 기존 파싱이 깨지지 않는지만 확인.

---

## 6. Codex가 발견한 부수 결함 (신규 태스크)

### T13. 공유 링크 — 상태변경 GET + 첨부 404 버그

**(a) 상태변경 GET**: `GET /api/share/{token}` → `resolve()`가 `incrementViewCount`(`ShareLinkService.java:91`). T5는 POST/PUT/PATCH/DELETE만 검증하므로, cross-site 내비게이션으로 열람 횟수를 소진시킬 수 있다(토큰을 알아야 하지만 실제 상태 변경이다).

**(b) 실제 기능 버그**: `validate()`가 `viewCount >= maxViews`로 거부하는데(`:78`), 첨부 서빙용 `nodeIdForAttachment`도 같은 `validate()`를 탄다(`:99`). 프런트는 본문 로드 후 첨부를 요청하므로(`SharePage.tsx:103`), **마지막 열람에서 본문은 보이는데 이미지가 전부 깨진다.**

수정: 열람 소진(카운트 증가)과 콘텐츠 접근 권한을 분리. 첨부 검증은 "이 열람 세션에서 이미 소비된 링크"를 허용해야 한다. (b)가 (a)보다 사용자 영향이 크다.

**테스트**: maxViews=1 링크 → 본문 1회 열람 후 첨부 요청 200 / 두 번째 본문 열람 404.

### T14. RateLimiter 메모리 상한

`AuthRateLimiter.java:21` `MAX_ENTRIES = 10_000`을 메모리 상한이라 주석했지만, `sweepIfOverflow`(`:87`)는 **5분 이상 비활동 항목만** 제거한다. 분산 버스트 시 활성 항목이 10,000을 무한정 초과할 수 있다.

수정: 스윕 후에도 상한 초과면 **결정적 축출**(가장 오래 touch된 것부터). 인메모리·재기동 초기화·단일 인스턴스 전제는 유지(폐쇄망 소규모의 의식적 트레이드오프).

축출 정책: **미잠금 우선 → LRU → 잠금은 최후 수단.** 잠금을 절대 축출하지 않으면 잠금 항목만으로 맵을 채워(고유 키 10,000개 × 각 5회 실패) 상한이 다시 무너지고, 아무거나 축출하면 잠금 조기 해제 = 시도제한 우회가 된다.

> **구현 중 발견한 추가 결함(계획에 없던 것)** — 정렬 **동률 처리에 공격자 조작 가능성**이 있었다. `touchedAt`은 시계 해상도 때문에 버스트 중 동률이 흔한데, 초안이 동률을 키 문자열로 깨면(`thenComparing(Map.Entry::getKey)`) 공격자가 피해자 키보다 사전순 뒤에 오는 계정명(`zzz-*`)으로 도배해 **피해자의 실패 카운터를 먼저 축출**시킬 수 있다 → 카운터 리셋 → 시도제한 우회. 상한을 고치려다 우회 경로를 만드는 셈이었다.
>
> 해소: `Entry.seq`(단조 증가 순번)로 동률을 깨 계정명이 순서에 전혀 영향을 주지 않는 진짜 LRU로. `eviction_doesNotDropActivelyCountingVictim_regardlessOfKeyOrder`로 고정했고, 비교자를 키 기반으로 되돌리면 실제로 FAIL함을 확인(vacuous 테스트 아님).
>
> 부수: `sweepIfOverflow()` 호출을 카운터 증가 **뒤로** 이동. 기존엔 증가 전 스윕이라 반환 시점 크기가 상한+2까지 튀었다.

---

## 7. 보류

| 항목 | 근거 |
|---|---|
| M-2 break-glass 복구 | 보안이 아니라 **가용성** 사안. Codex도 "가용성 도박이지 보안 필수는 아님"으로 판단. 호스트 운영자는 sqlite3로 직접 복구 가능 |
| TLS 실제 전환 | 인증서 발급이 사내 CA 프로세스. 앱측은 T12로 준비 완료 |
| `uuid` 9.0.1 → 11.x | 감사 권고를 그대로 적용하면 안 된다. `pnpm-workspace.yaml:6`에 반대 취지 기존 결정(mermaid 10.x가 uuid **v9 API** 의존). semver 범위 ≠ 런타임 호환. mermaid 실 import 경로 확인이 선행 |

### B2를 다시 열 때의 권고안 (기록)

```
java -jar worknote.jar --worknote.recover-admin=true   # + WORKNOTE_ADMIN_PASSWORD
```
→ admin 자격증명 재설정 + status active + 2FA 해제 → `admin.recovered` 감사 기록 → **정상 기동 없이 종료**.

---

## 8. 실행 방식 · 검증 기준

**TDD 필수** — 태스크마다 실패하는 테스트를 먼저 쓰고 통과시킨다. `Outline.tsx`는 특성화 테스트가 선행 조건이다.

**병렬 실행** — 파일이 겹치지 않는 태스크는 서브에이전트로 동시 진행.

| 웨이브 | 태스크 | 소유 파일 |
|---|---|---|
| 1 | T1·T2·T5 | `auth/AuthController.java`, `auth/AuthFilter.java` |
| 1 | T6~T11 | `admin/**`, `audit/**`, `V12__*.sql`, `frontend/src/admin/{api.ts,screens/Audit.tsx,auditDetail.ts}` |
| 1 | T13 | `share/**`, `frontend/src/share/**` |
| 1 | T4 | `frontend/src/components/Outline.tsx`, `frontend/src/admin/auditReport.ts` |
| 2 | T3·T12 | `attachment/**`, `config/**`, `application.yml` |
| 2 | T14 | `auth/AuthRateLimiter.java` |

배치 완료 시:

```
cd backend  && ./gradlew test     # 현재 491 passed 기준, 회귀 0
cd frontend && pnpm test          # 현재 343 passed 기준, 회귀 0
```

이후 Codex 리뷰 → 지적 수정 → 재리뷰(최대 3회) → PR·머지.
