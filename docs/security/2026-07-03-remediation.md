# 보안점검 조치 결과 — 2026-07-03 감사 대응

원본: `2026-07-03-security-audit.md` · 부속: `2026-07-03-secscan-sca.md`, `2026-07-03-findings.sarif`
브랜치: `fix/security-hardening` (base `04daef4`)

## 조치 완료

| 감사 항목 | 심각도 | 조치 | 커밋 |
|---|---|---|---|
| §3 SCA 39건 | 심각3·위험16·보통13·일반7 | Spring Boot 3.5.14 업그레이드 + BOM 핀(tomcat 10.1.55·jackson-bom 2.21.4·logback 1.5.33·assertj 3.27.7) | `c387432` |
| §2-1 인증 시도 제한 부재 | HIGH (CWE-307) | AuthRateLimiter — 계정 5회·IP 30회 실패 시 5분 잠금, 잠금 시 429 + `auth.lockout` 감사. login·2fa/verify·recover/verify 전 경로 적용 | `ce94dc4`, `b93c16f`, `8f55791` |
| §2-2 SSRF (Redmine base URL) | MEDIUM (CWE-918) | RedmineUrlValidator — scheme·userinfo·차단대역(loopback/link-local/any-local/multicast) 검증을 저장·호출 양 시점에 강제 + 응답 2MiB 캡. 사설 대역은 폐쇄망 정상 사용처라 허용 | `4abb382`, `9081105`, `fb740d6` |
| §2-3 복구코드 비밀번호 우회 | MEDIUM | pending 세션(1차 요소) 선행 강제 + 코드 8자리 숫자(26.6bit) → 12자 영숫자(59.4bit) 상향. 프런트 복구 UI 정합 | `62a1918`, `683417d`, `c9da597` |
| §4 SMTP 평문 다운그레이드 | Low | STARTTLS `required` + `ssl.checkserveridentity` 강제 | `6790a09` |
| §4 업로드 크기검사 전 힙 적재 | Low | precheck — `getBytes()` 전 선언 크기로 정책 선검사 | `68bdd79` |
| §4 pathOf 루트 접두 검증 부재 | Low | `startsWith(root)` 가드 추가 (store 쓰기 가드와 대칭) | `bc6ab81` |
| §4 공유 첨부 비이미지 서빙 | Low | 이미지 한정 확정 — shareList 필터 + shareDownload 비이미지 404(무효와 균등) | `7898695` |
| §4 Redmine 응답 무제한 읽기 | Info | 2MiB 상한(위 SSRF와 동일 커밋) | `fb740d6` |

## 설계상 감사 권고와 다른 결정

- **SSRF — 사설 대역 미차단**: 폐쇄망 인트라넷 Redmine(`http://redmine.intra`, `http://10.x.x.x` 등)이 정상 사용처라 사설 대역 차단 시 기능 자체가 불능. 차단은 loopback·link-local(클라우드 메타데이터 169.254.169.254 포함)·any-local(0.0.0.0)·multicast로 한정. `https` 강제도 안 함(폐쇄망 HTTP 전제).
- **복구 1차 요소 = pending 세션**: 별도 비밀번호 재입력 대신, 비밀번호 로그인으로 만들어진 `2faPending` 세션 + emp 일치를 요구. `verify2fa`와 동일 게이팅이며 프런트 플로우(로그인 → OTP 화면 → "복구 코드로 로그인")와 정합.

## 의도적 미조치 (수용한 잔여 위험)

- **CSP `unsafe-inline`/`unsafe-eval`** — mermaid 요구. 강화 시 nonce 기반 script-src 재설계 필요(별도 과제).
- **업로드 매직바이트 미검증** — 다운로드가 서버파생 MIME + `nosniff` + 비이미지 `attachment` 강제라 저장형 XSS 미유발. 콘텐츠 스푸핑 잔여 갭 수용.
- **rate-limit 인메모리** — 재기동 시 초기화. 단일 인스턴스·폐쇄망 소규모 전제 수용(다중 인스턴스 확장 시 공유 저장소 필요).
- **SSRF 호출 시점 재검증의 TOCTOU** — resolve-then-connect라 잔여 위험이 있으나(자바 HttpClient 제약), 저장 후 DNS 레코드 변경(리바인딩) 시나리오는 실질 차단.

## 배포 하드닝 체크리스트 (§5 — 운영 설정, 코드 무관)

- [ ] `WORKNOTE_MODE=server` 설정 — 기본 `local`은 무인증(단일 데스크톱 전제). 네트워크 배포 시 미설정하면 전 API 개방.
- [ ] TLS 종단 시 `application.yml` 쿠키 `secure: true` 활성화(현재 폐쇄망 HTTP 전제로 주석).
- [ ] 시크릿 env 주입 확인: `WORKNOTE_ADMIN_PASSWORD`, `WORKNOTE_2FA_KEY`(Base64 32B), `WORKNOTE_SMTP_*`.
- [ ] SMTP 서버가 STARTTLS 지원 시 `WORKNOTE_SMTP_STARTTLS=true` — 미지원 서버에 true면 발송 실패하니 사전 확인.
- [ ] `worknote.db` / `worknote-otp.db` / `seed_*.py`는 `.gitignore` 커밋 제외 확인.

## 검증

- 백엔드 `./gradlew clean test` — 전체 그린
- 프런트 `pnpm test` (343) + `pnpm build` — 그린
- 14 커밋 (`c387432`..`7898695`)
