# 관리자 2FA (TOTP) 설계

> 작성일: 2026-06-19 · 상태: 설계 확정(구현 대기) · 대상: backend(Spring Boot) + frontend(Vite/TS) · 적용 모드: **server 한정**

## 1. 배경 / 목표

work-note의 server 모드는 세션 기반 단일 요소 인증(사번 + 비밀번호, PBKDF2)만 제공한다. 관리자 계정 탈취 시 전체 vault와 권한 체계가 노출되므로, 관리자에 **2차 인증(2FA)** 을 도입한다.

- 방식: **TOTP (RFC 6238)** — Google Authenticator / FreeOTP / Authy 등 표준 앱 호환.
- **폐쇄망(air-gapped)에서 동작**: TOTP는 시드 + 시각으로 HMAC을 계산하는 오프라인 알고리즘이며 외부 통신이 없다. "Google OTP"는 앱 이름일 뿐 구글 서버와 통신하지 않는다.
- **전제(단 하나)**: 서버 시계와 사용자 휴대폰 시계가 ±30초 내로 일치해야 한다. 폐쇄망이면 내부 NTP 또는 수동 동기화가 필요하다(운영 문서에 명시).

### 목표
1. 전 사용자가 **선택적으로(opt-in)** TOTP 2FA를 켤 수 있다.
2. admin 권한(`admin.*`) 보유자는 **유예 기간 후 강제** 적용된다.
3. 폐쇄망에서 락아웃 시 **이메일 1회용 복구 코드**(사내 SMTP) 또는 **다른 관리자의 초기화**로 복구한다.
4. 시드는 **서버 키로 암호화(at-rest)** 저장한다.
5. 등록용 **QR을 서버가 오프라인 생성**한다(외부 차트 API 금지).

### 비목표 (YAGNI)
- WebAuthn/FIDO2, SMS, 푸시 승인, 하드웨어 토큰.
- **백업 코드 묶음(self-service N개)** — 복구는 관리자 초기화 + 이메일만 채택(결정 사항).
- local 모드 2FA — local은 무인증 단일 PC이므로 대상 아님.
- TOTP 알고리즘 협상(사용자별 SHA256/digits 선택) — 고정값으로 단순화.

## 2. 확정 결정 요약

| 항목 | 결정 |
|---|---|
| 대상 | 전 사용자 opt-in |
| admin 강제 | **유예 후 강제**(첫 로그인 + `grace_days` 경과 시 등록 완료까지 차단) |
| QR | **백엔드 생성**(zxing) — 런타임 외부통신 0 |
| 시드 저장 | **AES-256-GCM 암호화**, 키 = `WORKNOTE_2FA_KEY`(env) |
| 복구 | ① 다른 관리자 초기화 ② 이메일 **1회용 복구 코드**(사내 SMTP) |
| 알고리즘 | TOTP HMAC-**SHA1**, 6자리, 30초, 윈도우 ±1 step |
| 로그인 흐름 | **2단계**(비밀번호 → `2fa_required` → OTP 검증) |
| 의존성 | TOTP·Base32는 **외부 의존성 0 자체 구현**, QR만 zxing 빌드 의존성 추가 |

## 3. 아키텍처 개요

기존 커스텀 `AuthFilter`/`AuthService`(Spring Security 미사용) 위에 얹는다. 세션에 **부분 인증(2FA 대기)** 상태를 추가하고, TOTP 검증을 통과해야 완전 인증으로 승격한다.

```
[로그인]
POST /auth/login (emp, password)
  └ PBKDF2 검증 실패 → 401 (기존)
  └ 검증 성공
       └ 2FA 미사용(user_totp.enabled=0/없음)
            └ admin & 유예경과 & 미등록 → 완전 인증하되 me.totp.enforced=true (프런트가 등록 강제 화면)
            └ 그 외 → 완전 인증 (기존: SESSION_USER + SESSION_CRED)
       └ 2FA 사용(enabled=1)
            └ 부분 인증 세션: SESSION_USER + SESSION_2FA_PENDING=true (SESSION_CRED 미설정)
              응답 {status:"2fa_required"}
[OTP]
POST /auth/2fa/verify (code)  ← 부분 인증 세션에서만
  └ TOTP/복구코드 검증 성공 → SESSION_CRED 설정 + pending 제거 → 완전 인증
```

`AuthFilter`는 세 가지 상태를 구분한다:
- **미인증**(SESSION_USER 없음) → 401 (기존).
- **부분 인증**(SESSION_USER 있음 + SESSION_2FA_PENDING) → `/auth/2fa/verify`, `/auth/logout`만 허용, 그 외 401 + 헤더/바디로 `2fa_required` 신호.
- **완전 인증**(SESSION_CRED 일치) → 통과 (기존 credSalt 검사 유지).

## 4. 데이터 모델 (Flyway V9)

`backend/src/main/resources/db/migration/sqlite/V9__totp_2fa.sql`

```sql
-- 사용자별 TOTP 등록 정보 (user_credential처럼 분리 테이블)
CREATE TABLE user_totp (
  user_id      TEXT PRIMARY KEY REFERENCES app_user(id) ON DELETE CASCADE,
  secret_enc   TEXT NOT NULL,            -- AES-256-GCM(base64(nonce||ct||tag)) 암호화된 Base32 시드
  enabled      INTEGER NOT NULL DEFAULT 0, -- 0=등록 진행중(미확인), 1=활성
  confirmed_at TEXT,                      -- ISO-8601, enabled=1 전환 시각
  last_step    INTEGER NOT NULL DEFAULT 0, -- 마지막으로 성공한 TOTP step(재생 공격 방지)
  created_at   TEXT NOT NULL
);

-- 이메일 1회용 복구 코드 (해시 저장, 단기 만료)
CREATE TABLE totp_recovery (
  id         TEXT PRIMARY KEY,           -- "rc-" UUID
  user_id    TEXT NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
  code_hash  TEXT NOT NULL,              -- PBKDF2 또는 SHA-256(salt) of 복구 코드
  expires_at TEXT NOT NULL,              -- 발급 + 10분
  used       INTEGER NOT NULL DEFAULT 0,
  created_at TEXT NOT NULL
);
CREATE INDEX idx_totp_recovery_user ON totp_recovery(user_id);

-- admin 유예 추적: app_user에 컬럼 추가
ALTER TABLE app_user ADD COLUMN totp_grace_start TEXT; -- admin 첫 로그인 시각(유예 시작점), nullable

-- 정책 설정
INSERT INTO app_setting (key, value) VALUES ('2fa.grace_days', '7');
```

> SQLite는 `ALTER TABLE ... ADD COLUMN`만 지원 — 단순 nullable 컬럼이라 안전. 기존 마이그레이션 관례(FK off는 런타임 PRAGMA, 명명 DB 격리)는 [[worknote-test-inmemory-isolation]] 따른다.

## 5. TOTP 명세 (외부 의존성 0)

`com.worknote.auth.totp` 패키지. 순수 로직은 정적/순수 함수로 추출해 유닛 테스트(프로젝트 관례, §11).

### 5.1 알고리즘
- HMAC-**SHA1**(`javax.crypto.Mac` "HmacSHA1") — Google Authenticator 기본 호환.
- step = `floor(epochSeconds / 30)`.
- 6자리: RFC 4226 dynamic truncation → `binary % 10^6`, 좌측 0 패딩.
- 검증 윈도우: `step-1, step, step+1`(±30초 시계 흔들림 허용).
- **재생 방지**: 성공한 step ≤ `last_step` 이면 거부. 성공 시 `last_step = matchedStep`.

### 5.2 시드 / Base32
- 시드 = 20바이트 `SecureRandom` → **Base32(RFC 4648, 패딩 없음)** 인코딩.
- Base32 encode/decode 유틸 자체 구현(`commons-codec` 미보유). 순수 함수 → 유닛 테스트.

### 5.3 otpauth URI
```
otpauth://totp/work-note:{emp}?secret={BASE32}&issuer=work-note&algorithm=SHA1&digits=6&period=30
```
- `{emp}`/issuer는 URL 인코딩. label은 `work-note:{emp}` 형식.

### 5.4 테스트 벡터
- RFC 6238 부록 B 표준 테스트 벡터(시드 `12345678901234567890`, 알려진 시각 → 알려진 코드)로 회귀 고정.

## 6. 시드 암호화 (at-rest)

`com.worknote.auth.totp.SecretCipher`

- 알고리즘: **AES-256-GCM**. 키 = `WORKNOTE_2FA_KEY`(env) = Base64(32바이트).
- 저장 포맷: `base64( nonce(12B) || ciphertext || gcmTag(16B) )`.
- 복호화 실패/키 변경 → 해당 사용자 2FA 사용 불가(재등록 필요). 명시적 로깅.
- **키 미설정 + server 모드 + 2FA 사용 시도** → 등록/검증 엔드포인트에서 명확한 422/500 에러("2FA 키 미구성") + 부트 시 경고 로그. server 모드에서 2FA를 쓰려면 키 필수.
- **키 분실 = 전원 재등록**(시드 복호화 불가) — 운영 trade-off, 배포 시크릿으로 관리.

## 7. admin 유예 후 강제

- `PermissionService.isAdmin(user)`(기존: `admin.*` 5종 포함)로 admin 판정.
- admin이 로그인할 때 `app_user.totp_grace_start`가 NULL이면 `now`로 설정(유예 시작).
- 강제 판정: `isAdmin && user_totp.enabled != 1 && totp_grace_start + grace_days < now`.
  - 참이면 **완전 인증은 허용하되**, `me` 응답에 `totp.enforced=true` + `totp.graceExpired=true` → 프런트가 **등록 강제 화면**(다른 기능 진입 차단). 백엔드도 방어적으로, enforced 사용자의 비-2FA·비-me API 호출을 차단(미등록 admin은 등록 엔드포인트·me·logout만 허용).
  - 유예 중(미경과)이면 `totp.enforced=true, graceExpired=false` → 배너 권고만, 차단 없음.
- 비-admin은 grace 무관(opt-in만).

## 8. 복구

### 8.1 이메일 1회용 복구 코드
- 로그인 화면 "복구 코드 받기" → `POST /auth/2fa/recover/request {emp}`.
  - 사용자 조회 → **이메일 등록되어 있고 2FA enabled면** 8자리(또는 형식 결정) 코드 생성 → `code_hash` 저장(만료 10분, 기존 미사용 코드 무효화) → 사내 SMTP로 발송.
  - 계정 존재/이메일 유무를 응답으로 노출하지 않음(항상 "발송했다면 메일을 확인" 류 균등 응답 — 열거 방지). 발송 실패도 동일 응답 + 서버 로그.
- `POST /auth/2fa/recover/verify {emp, code}` → 코드 검증(만료·used·해시) → 성공 시 **해당 로그인만 완전 인증으로 승격 + 2FA 재등록 유도**(코드 used=1). 기존 시드는 즉시 무효화하지 않고, 사용자가 보안 탭에서 재등록/해제하도록 안내(또는 정책상 즉시 해제 후 재등록 — §13 오픈 이슈).
- **trade-off**: "사내 메일함 접근 = 2FA 우회". 폐쇄망 사내 메일이 인증된 채널이라는 전제하에 수용. 1회용·10분 만료·전 과정 audit 로깅으로 위험 축소.

### 8.2 관리자 초기화
- `admin.security` cap 보유자가 `POST /api/admin/users/{id}/2fa/reset` → `user_totp` 삭제(또는 enabled=0 + 시드 폐기) + 미사용 복구 코드 무효화 → 대상 사용자 재등록 유도.
- 단일 admin 락아웃 대비 최후 경로. 자기 자신 초기화도 허용(다른 기기에서).

### 8.3 SMTP 구성
- env: `WORKNOTE_SMTP_HOST`, `WORKNOTE_SMTP_PORT`, `WORKNOTE_SMTP_FROM`, (선택) `WORKNOTE_SMTP_USER`/`WORKNOTE_SMTP_PASSWORD`, `WORKNOTE_SMTP_STARTTLS`.
- 미설정 시 이메일 복구 자동 비활성(요청 시 균등 응답 유지하되 실제 발송 skip, 관리자 초기화만 안내). `spring-boot-starter-mail` 의존성 추가(또는 표준 `jakarta.mail`).

## 9. API 엔드포인트

### 인증(부분 인증/공개)
| 메서드 | 경로 | 설명 |
|---|---|---|
| POST | `/api/auth/login` | 기존 + 2FA 사용자면 `{status:"2fa_required"}` 반환(부분 인증 세션) |
| POST | `/api/auth/2fa/verify` | 부분 인증 세션에서 TOTP 코드 검증 → 완전 인증 |
| POST | `/api/auth/2fa/recover/request` | `{emp}` → 이메일 복구 코드 발송(균등 응답) |
| POST | `/api/auth/2fa/recover/verify` | `{emp, code}` → 복구 코드 검증 → 완전 인증 + 재등록 유도 |

### 본인 2FA 관리(완전 인증 필요)
| 메서드 | 경로 | 설명 |
|---|---|---|
| GET | `/api/me/2fa` | 상태(enabled, enforced, graceExpired, emailPresent) |
| POST | `/api/me/2fa/setup` | 시드 생성(enabled=0) → otpauth URI 반환. **이메일 미기재면 422 + 안내** |
| GET | `/api/me/2fa/qr` | setup 중 시드의 QR PNG(zxing) |
| POST | `/api/me/2fa/confirm` | `{code}` 검증 → enabled=1, confirmed_at |
| DELETE | `/api/me/2fa` | 본인 해제(현재 비밀번호 또는 현재 OTP 재확인). admin이 enforced면 해제 차단 |

### 관리자
| 메서드 | 경로 | 설명 |
|---|---|---|
| POST | `/api/admin/users/{id}/2fa/reset` | 2FA 초기화(`admin.security`) |
| (확장) | `GET /api/admin/users` | 응답에 2FA 상태(`totpEnabled`) 추가 |

> `me` 응답(`MeResponse`)에 `totp` 객체(enabled/enforced/graceExpired/emailPresent) 추가. 프런트 mappers 단일 출처 유지.

## 10. 프런트엔드

기존 `React.createElement`/no-JSX, reducer/커스텀 훅/커맨드 패턴 계승. 결정 로직은 순수 함수로 추출(테스트 관례 [[worknote-frontend-test-convention]]).

- **로그인(`login/LoginPage.tsx`)**: `submitLogin`이 `2fa_required` 응답 시 OTP 입력 단계로 전환. OTP 6자리 입력 → `/auth/2fa/verify`. "복구 코드 받기" 링크 → emp 입력 → 발송 → 코드 입력 → `recover/verify`.
- **보안 설정 탭(본인)**: 등록 플로우(설명 → setup → QR 표시 + 수동 입력용 Base32 → 코드 입력 confirm → 완료), 활성 상태/해제. **이메일 미기재 시 등록 버튼 비활성 + "복구 불가, 이메일 먼저 등록" 안내**(요청 사항).
- **admin 강제**: `me.totp.enforced && graceExpired`면 등록 강제 화면(앱 진입 차단, 등록 모달). 유예 중이면 상단 배너 권고.
- **관리자 사용자 화면(`admin/screens/Users` 등)**: 사용자별 2FA 상태 칩 + "2FA 초기화" 액션(`admin.security` 가드, 기존 역할 편집 [[worknote-admin-role-edit]] 패턴의 확인 모달 재사용).
- **공통 UI**: 로그인/설정의 코드 입력·QR 표시 컴포넌트는 재사용 가능하게 분리.

## 11. 테스트 전략 (프로젝트 관례)

React 렌더/e2e 프레임워크 없음 → **결정 로직을 순수 함수로 추출해 vitest 유닛 테스트**, 실화면은 `/qa` browse. 백엔드는 JUnit.

- **백엔드 유닛**: TOTP 생성/검증(RFC 6238 벡터), Base32 round-trip, AES-GCM round-trip, 윈도우/재생 방지(last_step 경계), grace 판정 순수 함수, 복구 코드 검증(만료·used·해시), 균등 응답(계정 열거 방지) 동작.
- **백엔드 통합**: 명명 DB 격리([[worknote-test-inmemory-isolation]])로 로그인 2단계 흐름, 부분 인증 세션 차단, admin 강제 차단, 관리자 초기화, 복구 코드 e2e.
- **프런트 유닛**: 로그인 상태 머신(2fa_required 전환), enforced/graceExpired → 화면 모드 판정, 이메일 미기재 등록 차단 판정 등 순수 함수.

## 12. 보안 고려사항

- TOTP 코드/복구 코드 **타이밍 균등 비교**(`MessageDigest.isEqual` 류) — 기존 `PasswordHasher` 관례 일관.
- 복구 요청은 **계정 열거 방지** 균등 응답.
- 부분 인증 세션은 TOTP 검증/복구/logout 외 모든 API 차단.
- 2FA 검증 실패·복구 발송/사용·관리자 초기화·등록/해제 전부 `audit_log` 기록.
- `changeSessionId()`로 세션 고정 방어(기존) — 완전 인증 승격 시점에도 적용 검토.
- enabled admin이 본인 2FA 해제(`DELETE /me/2fa`) 시 enforced면 차단(우회 방지).

## 13. 오픈 이슈 / 구현 시 결정

- 복구 코드 verify 성공 후 기존 시드 **즉시 폐기 vs 유지+재등록 유도** — 보안상 즉시 폐기(=enabled 0)가 안전. 기본값 **즉시 폐기 후 재등록 강제**로 제안.
- 복구 코드 자리수/형식(8자리 숫자 vs base32 8자) — 8자리 숫자로 제안(메일 입력 편의).
- enforced admin 차단 범위를 백엔드 필터에서 어디까지 강제할지(전 API vs 쓰기 API만) — me/2fa-setup/logout 화이트리스트 외 전부 차단으로 제안.

## 14. 환경변수 요약

| 변수 | 필수 | 설명 |
|---|---|---|
| `WORKNOTE_2FA_KEY` | server+2FA 사용 시 필수 | Base64(32B) AES 키 |
| `WORKNOTE_SMTP_HOST` / `_PORT` / `_FROM` | 이메일 복구 사용 시 | 사내 SMTP 릴레이 |
| `WORKNOTE_SMTP_USER` / `_PASSWORD` / `_STARTTLS` | 선택 | SMTP 인증/암호화 |
| `2fa.grace_days`(app_setting) | — | admin 유예일(기본 7) |

## 15. 의존성 추가

- `com.google.zxing:core` (+ `javase`) — QR PNG 생성. 빌드 타임 의존성, 런타임 외부통신 0.
- 메일: `spring-boot-starter-mail`(또는 `jakarta.mail`).
- TOTP/Base32/AES는 **JDK 표준만** 사용(추가 의존성 없음).

---

관련 메모리: [[worknote-backend-phase2-core]] [[worknote-backend-phase3-admin-api]] [[worknote-frontend-test-convention]] [[worknote-test-inmemory-isolation]] [[worknote-admin-role-edit]]
