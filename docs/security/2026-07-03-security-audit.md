# 보안점검 종합 보고서 — work-note

- **점검일**: 2026-07-03
- **대상**: `work-note` (backend: Spring Boot 3.5.0 / Java 21 / MyBatis / Flyway / SQLite · frontend: Vite/React/TS)
- **방식**: `secscan` 결정적 스캔(SCA + Secret + SAST + 도달성 분석) + 4개 도메인 수동 심층 리뷰(인가/IDOR · SSRF · 파일업로드 · 암호/토큰/세션) + 프론트엔드 XSS 확인
- **커버리지**: backend 소스 약 130개 파일, MyBatis 매퍼 14개, 프론트 마크다운 렌더 경로
- **부속 산출물**: `2026-07-03-secscan-sca.md`(의존성 상세), `2026-07-03-findings.sarif`(SARIF)

---

## 1. 결론

**설계 품질이 높다.** 인젝션·인가·암호·업로드 등 핵심 공격면은 모두 올바르게 방어돼 있다. 실제 조치가 필요한 **코드 결함은 3건**(HIGH 1 · MEDIUM 2)이며, 그 외 39건은 서드파티 의존성 CVE로 **Spring Boot 업그레이드 한 번**으로 해소된다. 나머지는 Low/Info 하드닝과 배포 설정 항목이다.

| 구분 | 건수 |
|---|---|
| 🔴 조치 필요 (코드) | HIGH 1 · MEDIUM 2 |
| 📦 의존성(SCA) | 심각 3 · 위험 16 · 보통 13 · 일반 7 (전부 도달불가, 업그레이드로 해소) |
| 🟡 Low / Info 하드닝 | 5건 |
| ✅ 검증 후 이상無 | 8개 영역 |

---

## 2. 조치 필요 (코드 결함)

### [HIGH] 2-1. 인증 엔드포인트에 시도 제한 부재 (CWE-307)

- **위치**: `auth/AuthController.java` — `login`(71–101), `verify2fa`(103–115), `recoverVerify`(132–153) · `auth/totp/Totp.java:30`
- **문제**: 로그인·2FA 검증·복구 어디에도 rate-limit / lockout / 실패 카운터가 없다(전 코드베이스 grep 0건).
- **악용 시나리오**:
  - **2FA 우회** (가장 날카로움): 공격자가 비밀번호를 이미 확보(피싱/재사용)해 `2faPending` 세션을 얻으면, 6자리 TOTP(10⁶)를 **무제한** 추측할 수 있다. 오답은 `lastStep`을 전진시키지 않아(=코드가 소모되지 않음, `Totp.java:30` `if (s <= lastStep) continue`) pending 세션이 유지되는 한 여러 스텝-윈도우에 걸쳐 누적 시도가 가능하다.
  - **크리덴셜 스터핑**: `/api/auth/login`에 lockout이 없어 비밀번호 대입이 무제한.
- **근거**: `verify2fa`의 실패 처리는 `audit.logRaw(...); throw ...`가 전부 — 카운터/지연 없음. `login`도 동일.
- **수정 방향**: 계정 + IP 단위 실패 카운터로 N회 초과 시 지수 backoff / 일시 lockout. 우선순위 `/2fa/verify` ≥ `/login` ≥ `/2fa/recover/verify`. (Spring Security 미사용이므로 필터/인터셉터 레벨에서 직접 구현하거나 bucket 라이브러리 도입.)

### [MEDIUM] 2-2. SSRF — Redmine base URL 미검증 (+ 전 사용자 토큰 탈취)

- **위치**: `redmine/RedmineClient.java:41–50`(sink) · `setting/SettingService.java:63–67`(source, `setRedmine`) · `admin/AdminSettingController.java:67–71`(쓰기 진입점)
- **문제**: `setRedmine`이 URL을 `trim()`만 하고(`SettingService.java:66`), `RedmineClient.get`이 `URI.create(base + path)`로 그대로 요청한다(`RedmineClient.java:45`). scheme/host/사설IP 검증이 요청 경로 어디에도 없다.
- **악용 시나리오**: admin(또는 탈취된 admin 세션, 혹은 URL 오타·오설정)이 base를 `http://169.254.169.254/latest/meta-data/…`·`http://10.0.0.5:6379/` 등으로 지정하면, 서버가 내부망을 대신 조회하고 응답 본문이 인증 사용자에게 반환된다(`GET /api/redmine/issues` → full-response SSRF). **더 큰 영향**: base를 공격자 서버로 지정하면 매 호출마다 `X-Redmine-API-Key`로 **모든 사용자의 복호화된 Redmine 토큰이 그 URL로 유출**된다(`RedmineClient.java:47`).
- **심각도 근거**: 쓰기 경로가 admin+세션 게이팅(`AdminSettingController.java:70` `guard.requireAdmin`)이라 미인증 공격자에겐 도달 불가 → MEDIUM. admin 세션이 피싱/CSRF 가능하거나 준-admin 설정 역할이 추가되면 상승.
- **수정 방향**: `setRedmine`에서 `https` scheme 강제 + 호스트 allowlist(또는 DNS 해석 후 loopback/link-local/private/`169.254.169.254`/`::1` 거부), **fetch 시점 재검증**(DNS 리바인딩 방어). 응답 크기 상한도 함께(아래 Info).

### [MEDIUM] 2-3. 복구 코드가 비밀번호를 우회 (저엔트로피 단일 인증 경로)

- **위치**: `auth/AuthController.java:132–153`(`recoverVerify`) · `auth/AuthFilter.java:32`(미인증 허용목록) · `auth/totp/RecoveryCodec.java`(엔트로피)
- **문제**: `/api/auth/2fa/recover/verify`가 미인증 허용목록에 있고, `emp` + 이메일로 받은 **8자리 숫자 코드(≈26.6 bit)** 만으로 **비밀번호 검증 없이** 완전 인증 세션을 발급하고(`SESSION_CRED` 설정, `:150`) 기존 TOTP를 폐기한다(`:144`).
- **악용 시나리오**: 이메일 수신함 탈취(또는 lockout 부재를 이용한 코드 브루트포스)만으로 **비밀번호 + TOTP 양쪽을 모두 우회**하는 매직링크형 로그인이 된다. 2FA를 켠 계정이 사실상 "이메일 단일 인증"으로 격하된다.
- **완화 현황(정상 동작)**: 복구 코드는 PBKDF2(120k) 해시로 저장·상수시간 비교·1회용·10분 만료·재요청 시 기존 무효화 → 단일 윈도우 내 완전 브루트포스는 현재 비현실적. 그러나 **실패-lockout이 없고**(2-1) 엔트로피가 낮으며 **1차 요소(비밀번호)를 요구하지 않는 설계**가 근본 약점.
- **수정 방향**: 복구 코드 검증 전에 비밀번호(1차 요소)를 요구, 코드 엔트로피 상향(알파뉴메릭 10자+), 2-1의 rate-limit 병행.

---

## 3. 의존성(SCA) — 39건, 전부 서드파티 · 도달불가

전 항목이 이행(transitive) 의존성 CVE이며 도달성 분석 결과 **앱 코드가 취약 함수를 직접 호출하지 않음(도달불가)**. 주범과 심각(Critical) 3건:

| 심각도 | CVE | 컴포넌트 | 수정 버전 |
|---|---|---|---|
| 심각 | CVE-2026-41293 (CWE-20) | tomcat-embed-core 10.1.41 | 10.1.55 / 11.0.22+ |
| 심각 | CVE-2026-43512 (CWE-592) | tomcat-embed-core 10.1.41 | 10.1.55 / 11.0.22+ |
| 심각 | CVE-2026-43515 (CWE-285 인가) | tomcat-embed-core 10.1.41 | 10.1.55 / 11.0.22+ |
| 위험 | CVE-2026-54512 (CWE-502 역직렬화) | jackson-databind 2.19.0 | 2.18.8 / 2.21.4+ |
| 위험 | CVE-2025-41249 (CWE-285) | spring-core 6.2.7 | 6.2.11+ |
| … | (전체 39건은 `2026-07-03-secscan-sca.md` / SARIF 참조) | | |

- **단일 조치**: `backend/build.gradle`에서 **`org.springframework.boot` 3.5.0 → 3.5.14+**. Spring Boot BOM이 tomcat-embed-core·jackson·spring·logback 패치 버전을 연쇄로 끌어와 대부분/전부 해소된다. (`assertj-core`는 test 의존성 — 별도 `3.27.7+`.)
- ⚠️ **도달성 "불가" 해석 주의**: leaf 라이브러리엔 유효한 우선순위 강등 근거지만, **tomcat-embed-core(서블릿 컨테이너)·spring-webmvc 같은 프레임워크 라이브러리엔 약한 근거**다 — 런타임에 프레임워크가 자기 코드를 호출하므로 정적분석이 호출 그래프를 놓친다. **심각 3건 포함 업그레이드를 권장**하며, 억제(suppression)는 부적절하다.

---

## 4. Low / Info 하드닝

| 심각도 | 항목 | 위치 | 조치 |
|---|---|---|---|
| Low | SMTP opportunistic STARTTLS(기본 평문) → 자격증명·복구코드 평문 노출 | `auth/totp/SmtpMailSender.java:24` · `MailConfig.java:21` | `user` 설정 시 `mail.smtp.starttls.required=true` + `ssl.checkserveridentity=true` (또는 SMTPS) |
| Low | 업로드 크기검사 이전 전체 파일을 힙에 적재(제한적 DoS) | `attachment/AttachmentController.java:60` + `AttachmentService.java:38–39` | `getBytes()` → `getInputStream()` 스트리밍, 또는 multipart 상한을 정책 최대값(25MB)에 맞춤 |
| Low | `pathOf()`에 루트 접두 검증 부재(심층방어 공백, 현재 미악용) | `attachment/AttachmentService.java:71–73` (cf. store 가드 `42–44`) | `if (!p.startsWith(root)) throw ...` 대칭 추가 |
| Low | 공유 첨부 서빙이 maxViews 예산 미소모 + 비이미지까지 서빙 | `attachment/AttachmentController.java:116–126` · `share/ShareLinkService.java:100–103` | 열람수 정책 적용 또는 이미지 한정 여부 설계 확정 |
| Info | CSP `script-src`에 `'unsafe-inline' 'unsafe-eval'`(mermaid 요구) | `web/SecurityHeadersFilter.java:23` | 문서화된 트레이드오프. 강화 시 nonce 기반 script-src(Vite 빌드 연동) |
| Info | 업로드 매직바이트 미검증(확장자 allowlist만) | `attachment/UploadPolicy.java` | 다운로드가 서버파생 MIME+nosniff+attachment라 XSS 미유발 — 콘텐츠 스푸핑 잔여 갭 |
| Info | Redmine 응답 무제한 읽기(SSRF DoS 증폭) | `redmine/RedmineClient.java:50` | 스트리밍 핸들러로 바이트 상한 |

---

## 5. 배포 하드닝 체크리스트 (코드 결함 아님, 운영 설정)

- [ ] **`WORKNOTE_MODE=server` 설정** — 기본값 `local`은 **무인증**(단일 사용자 데스크톱 전제). 네트워크 배포 시 미설정하면 전 API가 개방된다. (`application.yml:27`)
- [ ] **TLS 종단 시 쿠키 `secure: true` 활성화** — 현재 폐쇄망 HTTP 전제로 주석 처리됨. (`application.yml:25`)
- [ ] **필수 시크릿 env 주입 확인** — `WORKNOTE_ADMIN_PASSWORD`, `WORKNOTE_2FA_KEY`(Base64 32B), `WORKNOTE_SMTP_*`. (코드·설정에 하드코딩 없음 — 정상)
- [ ] `worknote.db` / `worknote-otp.db` / `seed_*.py`는 `.gitignore`로 커밋 제외 확인됨(로컬 전용).

---

## 6. 검증 후 이상無 (손대지 말 것 — 올바르게 구현됨)

| 영역 | 근거 |
|---|---|
| **SQL 인젝션** | MyBatis 매퍼 14개 전부 `#{}` 파라미터 바인딩, `${}` 0건. 명령 인젝션·역직렬화 싱크도 없음 |
| **인가 / IDOR / 권한상승** | 모든 리소스 엔드포인트가 객체의 **실제 소유 노드** 기준으로 가드(`AttachmentController.java:78–90` `requireRead(row.nodeId())` 등). admin 컨트롤러 전 메서드 `requireAdmin` 1:1(`AdminGuard.java:19–30`). signup은 `visitor`/`pending` 하드코딩(`AuthService.java:68`), 자기 역할·상태 변경 차단(`UserAdminService:69`). null/미지 리소스에 **fail-closed**(`VaultGuard.java:22–23`) |
| **암호 프리미티브** | RNG 5종 전부 `SecureRandom`. PBKDF2-HMAC-SHA256 120k회 + 16B 사용자별 salt + `MessageDigest.isEqual` 상수시간 비교 + 미존재계정 dummy-hash(계정열거 차단). TOTP 시드 at-rest AES-256-GCM(랜덤 12B nonce + 태그검증), 재생 방지(`Totp.java:30`) |
| **세션 관리** | 로그인 시 `changeSessionId()`(세션 고정 방어, `AuthController.java:81`), 권한상승 시 재발급(`:147,162`), 로그아웃 `invalidate()`(`:191`), 매 요청 status+salt DB 재검증(비활성화·비번리셋 즉시 무효화) |
| **파일 업로드** | 디스크 경로는 서버 `UUID`만으로 구성(클라 파일명 경로 미사용) → 경로순회 불가. 다운로드 `requireRead(nodeId)`. 비이미지 강제 `Content-Disposition: attachment` + 서버파생 MIME + `nosniff`(저장형 XSS 차단). RFC 5987 `filename*` 인코딩(헤더 인젝션 차단) |
| **공유 토큰** | 32B(256bit) `SecureRandom` → base64url. 만료·취소·최대열람수·pin 허용목록 모두 강제 |
| **Redmine 토큰 저장** | AES-256-GCM 암호화 at-rest, 미로깅, 클라이언트 미반환. 사용자 제어 host 불가, 리다이렉트 미추적, 타임아웃(connect 5s/req 10s) |
| **프론트엔드 XSS** | 마크다운 렌더 최종 출력 `DOMPurify.sanitize`(`lib/markdown.ts:183`). mermaid `securityLevel:"strict"` + `htmlLabels:false` + SVG 재-sanitize(foreignObject 금지, `:198–201`). 이미지 src 내부/상대경로 제한 |
| **보안 헤더 / CSRF / CORS** | CSP(default-src 'self' · object-src 'none' · frame-ancestors 'none') + nosniff + X-Frame-Options DENY + Referrer-Policy no-referrer(공유토큰 URL 유출 차단). CSRF는 SameSite=lax 쿠키 + JSON @RequestBody. CORS 미설정(동일출처만) |

---

## 7. 우선순위 요약

1. **Spring Boot 3.5.0 → 3.5.14+** (§3) — 리스크 최저, 효과 최대(심각 3 포함 39건 해소)
2. **인증 rate-limit/lockout** (§2-1, HIGH) — 특히 `/2fa/verify`·`/login`
3. **SSRF URL 검증** (§2-2, MEDIUM) — `setRedmine` + fetch 재검증
4. **복구코드 1차요소 요구 + 엔트로피 상향** (§2-3, MEDIUM)
5. §4 Low/Info + §5 배포 설정

> 방법론 주: HIGH·MEDIUM 3건은 해당 코드를 직접 재확인해 등재. §6 "이상無"는 각 도메인 전용 리뷰로 공격면을 추적해 확인한 결과다. 도달성 판정은 리플렉션·DI·애노테이션 라우팅을 놓칠 수 있으므로 안전 보증이 아니라 우선순위 신호로만 사용한다.
