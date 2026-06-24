# Redmine 이슈 임포트(클리퍼) 설계

> 작성일: 2026-06-24 · 상태: 설계 확정(구현 대기) · 대상: backend(Spring Boot) + frontend(Vite/TS) · 적용 모드: **server 한정**

## 1. 배경 / 목표

사내에서 Redmine을 프로젝트/이슈 관리에 사용 중이다. 노트 작성 시 Redmine 이슈의 내용(설명·댓글·메타)을 손으로 옮겨 적는 비용을 없애고자, **work-note 안에서 Redmine 이슈를 열어 필요한 부분만 골라 마크다운으로 삽입**하는 임포트 기능을 도입한다.

- 둘 다 폐쇄망 내부에 있으므로 **백엔드가 Redmine REST API를 서버-투-서버로 직접 호출**한다(외부 인터넷 불필요). 프런트는 Redmine에 직접 접근하지 않는다(CORS/네트워크 격리 회피, 키 노출 방지).
- **클리퍼(clipper) 방식**: 이슈를 통째로 노트로 변환·동기화하지 않는다. 분할 작업창에서 사용자가 메타표/본문/댓글을 **블록 단위로 골라 삽입**한다. → 새로고침·덮어쓰기·동기화 충돌이 **원천적으로 없다**(들어간 것은 사용자가 고른 것뿐, 이후엔 평범한 노트 내용).

### 목표
1. server 모드 사용자가 **본인 Redmine API 키**를 등록하면, 본인 권한 범위의 이슈를 work-note 안에서 검색/열람할 수 있다.
2. 노트 편집 중 **분할 작업창**(넓으면 좌/우, 좁으면 상/하)으로 Redmine 이슈를 열고, 메타표·본문·댓글을 골라 **커서 위치에 마크다운으로 삽입**한다.
3. Redmine 호출은 **항상 호출자 본인 키**로 수행 → Redmine ACL을 그대로 따른다(work-note deny-우선 모델과 일관).

### 비목표 (YAGNI)
- **양방향 동기화 / 노트→이슈 푸시 / 실시간 상태 칩** — 클리퍼 1방향 임포트만.
- **노트의 영구 source-link 동기화**("이 이슈 다시 열기"·델타 갱신) — 2차 후보.
- **Textile 변환** — 사내 Redmine이 Markdown/CommonMark 모드로 확정(§2). Textile 인스턴스 대응은 비대상.
- **위키 페이지·시간추적·리포지토리** 임포트 — 1차는 이슈 한정(위키는 2차 후보).
- **첨부 파일 임포트** — 1차는 텍스트(본문/댓글)만, 첨부는 2차(별도 다운로드 중계 API라 분리).
- **local 모드 지원** — local은 무인증 단일 PC이므로 대상 아님(server 전용).
- **드래그앤드롭 삽입** — 1차는 `[삽입]` 버튼, DnD는 2차.

## 2. 확정 결정 요약

| 항목 | 결정 |
|---|---|
| 방향 | **임포트(클리퍼) 1방향** — 분할창에서 블록 선택 삽입 |
| 데이터 | **이슈**(본문 + 댓글/journals + 메타). 위키·첨부는 2차 |
| 선택 방식 | **인앱 검색 + 내 이슈 목록**(assigned_to=me·open) + ID/URL 직접 조회 |
| 인증 | **사용자별 Redmine API 키** — 프로필 등록, **AES-256-GCM 암호화**(2FA 시드 유틸 재사용), 등록 시 검증 |
| 텍스트 포맷 | **Markdown/CommonMark**(사내 설정) — 본문 거의 그대로 삽입, 변환 최소 |
| 적용 모드 | **server 전용** |
| 갱신 정책 | 없음(클리퍼) — 삽입된 내용은 일반 노트 텍스트 |
| 진입점 | 에디터 툴바 버튼(슬래시 커맨드는 2차) |
| 의존성 | **신규 0** — Redmine REST는 JDK `HttpClient`, AES는 기존 `SecretCipher` 재사용 |

## 3. 아키텍처 개요

```
[설정] 관리자: redmine.base_url, redmine.enabled (app_setting)
[키 등록] 사용자: 프로필 → 키 입력 → 백엔드가 GET /users/current.json 로 검증 → AES 암호화 저장
[가져오기] 노트 편집 중 툴바 "Redmine 가져오기"
  ├ 좌(또는 상): 이슈 검색바 + "내 이슈" 목록
  │    └ 백엔드 프록시 GET /api/redmine/issues?... → Redmine GET /issues.json (본인 키)
  │ 이슈 클릭 → 상세 로드
  │    └ 백엔드 프록시 GET /api/redmine/issues/{id} → Redmine GET /issues/{id}.json?include=journals
  │       → 정규화(메타/본문/댓글) 응답
  └ 우(또는 하): 현재 노트 에디터
       └ 좌측 블록 [삽입] → 커서 위치에 마크다운 삽입(insertRedmineBlock)
```

- 프런트는 **백엔드 프록시만** 호출한다(`/api/redmine/*`). 프런트가 base_url/키를 알 필요 없음.
- 모든 프록시 호출은 **세션의 사용자 본인 키**를 서버가 꺼내 사용 → Redmine ACL 그대로. 키 미등록이면 기능 비활성.
- `redmine.enabled=false` 또는 base_url 미설정이면 전 엔드포인트 404/403.

## 4. 데이터 모델 (Flyway V11)

> ⚠️ **마이그레이션 번호 조정**: 커밋된 최신은 V9, 다른 작업 브랜치가 **V10**(`V10__rename_operator_role.sql`)을 점유 중. 따라서 이 기능은 **V11**을 쓴다. 머지 시점에 V10이 확정되면 번호 충돌 없는지 재확인.

`backend/src/main/resources/db/migration/sqlite/V11__redmine_token.sql`

```sql
-- 사용자별 Redmine API 키 (user_totp처럼 분리 테이블, 시드와 동일 AES 보관)
CREATE TABLE user_redmine_token (
  user_id        TEXT PRIMARY KEY REFERENCES app_user(id) ON DELETE CASCADE,
  token_enc      TEXT NOT NULL,            -- AES-256-GCM(base64(nonce||ct||tag)) 암호화된 Redmine API 키
  redmine_login  TEXT,                     -- 검증 시 GET /users/current 가 반환한 login (표시용·진단)
  last_verified_at TEXT,                   -- ISO-8601, 마지막 검증 성공 시각
  created_at     TEXT NOT NULL
);

-- 관리자 설정(미설정이면 기능 비활성)
INSERT INTO app_setting (key, value) VALUES ('redmine.enabled', '0');
INSERT INTO app_setting (key, value) VALUES ('redmine.base_url', '');
```

> SQLite 관례(런타임 PRAGMA로 FK off, 테스트는 명명 DB 격리)는 [[worknote-test-inmemory-isolation]] 따른다. 노트에 영구 link 컬럼은 두지 않는다(클리퍼 = 삽입형, 출처는 본문 `redmine #id` 텍스트로 남음).

## 5. 시드/키 암호화 (at-rest)

- **기존 `com.worknote.auth.totp.SecretCipher`(AES-256-GCM) 재사용**. 키 = 기존 `WORKNOTE_2FA_KEY`(env) 공용 또는 별도 `WORKNOTE_REDMINE_KEY` 중 택1.
  - **결정**: 별도 키 도입은 운영 부담만 늘리므로 **`WORKNOTE_2FA_KEY` 공용**(동일 AES-GCM, 평문 포맷 동일). 키 미설정 + server + Redmine 사용 시도 → 키 등록/사용 엔드포인트에서 명확한 422("암호화 키 미구성") + 부트 경고 로그.
- 저장 포맷: `base64( nonce(12B) || ciphertext || gcmTag(16B) )` (2FA와 동일).
- 키 분실 = 전원 키 재등록(복호화 불가). 운영 trade-off.

## 6. Redmine REST 클라이언트

`com.worknote.redmine.RedmineClient` — JDK `java.net.http.HttpClient`(추가 의존성 0).

- 베이스: `app_setting('redmine.base_url')`. 인증 헤더: `X-Redmine-API-Key: {복호화된 사용자 키}`.
- 타임아웃(연결/응답) 설정. 폐쇄망 내부 호출이므로 프록시 없음.
- 에러 매핑:
  - 401/403 → `RedmineAuthException`(키 무효/만료) → 프록시가 412/409로 "키 재등록 필요" 안내.
  - 404 → 이슈 없음 **또는 권한 없음**(Redmine은 미열람 이슈를 404로 줌) → 프록시 404.
  - 422/5xx → `RedmineUpstreamException` → 502/504 상류 오류.
- 응답 파싱(JSON, Jackson 기존 사용):
  - `IssueSummary { id, subject, status_name, assigned_to_name, project_name, updated_on }`
  - `IssueDetail extends Summary { description(MD), priority_name, due_date, journals[]{ user_name, created_on, notes(MD) }, (2차: attachments[]) }`
- **본문/댓글의 비어있는 journal(상태변경만 있고 notes 없는 항목)은 제외** — 텍스트 댓글만 노출.

## 7. API 엔드포인트 (server·완전 인증 필요)

### 본인 Redmine 키 관리
| 메서드 | 경로 | 설명 |
|---|---|---|
| GET | `/api/me/redmine` | 상태: `{ enabled(전역), tokenPresent, redmineLogin, lastVerifiedAt }` |
| PUT | `/api/me/redmine/token` | `{ token }` → `GET /users/current` 로 검증 후 AES 저장. 검증 실패 422, 키 미구성 422 |
| DELETE | `/api/me/redmine/token` | 본인 키 삭제 |

### 이슈 프록시 (본인 키 사용)
| 메서드 | 경로 | 설명 |
|---|---|---|
| GET | `/api/redmine/issues?query=&assignedToMe=&statusId=&projectId=&offset=&limit=` | Redmine `GET /issues.json` 중계 → `IssueSummary[]` + total. `assignedToMe=true`면 `assigned_to_id=me` |
| GET | `/api/redmine/issues/{id}` | `GET /issues/{id}.json?include=journals` 중계 → 정규화 `IssueDetail` |

- 공통 가드: `redmine.enabled=0` 또는 base_url 공백 → 404. 본인 키 미등록 → 409(`{code:"redmine_token_missing"}`) → 프런트가 등록 유도.
- 감사: `redmine.token.set` / `redmine.token.delete` / `redmine.import`(target=`issue#{id}`, 삽입 시 프런트 핑 또는 상세 조회 시 기록) `audit_log`에 적재.

> `me` 응답(`MeResponse`)에 `redmine` 객체(enabled/tokenPresent) 추가 → 프런트가 툴바 버튼 노출 판정. **MeResponse 필드 추가 = 생성자 인자 파급**([[worknote-2fa-totp-plan]]의 MeResponse 7인자 교훈) — 호출처 일괄 수정 필요.

## 8. 프런트엔드

기존 `React.createElement`/no-JSX, reducer/커스텀 훅/커맨드 패턴 계승. 결정 로직은 순수 함수로 추출([[worknote-frontend-test-convention]]).

- **진입점**: 에디터 툴바 "Redmine 가져오기" 버튼 — `me.redmine.enabled && tokenPresent`일 때만 노출(키 없으면 "키 등록 안내"로 프로필 유도).
- **분할 패널(`components/RedmineImportPanel.tsx`)**:
  - 가져오기 누르면 에디터 영역이 **좌(Redmine 뷰어) / 우(현재 노트 에디터)** 로 분할, 닫으면 노트 풀폭 복귀.
  - 반응형: 컨테이너 `display:flex`, **넓으면 `row`(좌/우) · 좁으면 `column`(상/하)** — 분할 방향을 **순수함수 `splitDirection(width)`** 로 결정(임계폭 상수, 테스트 대상). CSS 변수·기존 모달/패널 패턴 재사용.
  - 좌측 상단: 검색바 + "내 이슈" 토글(assignedToMe) + 상태 필터 → 결과 목록.
  - 좌측 본문: 선택 이슈의 **메타표 / 본문 / 댓글 N개**, 각 블록 헤더에 `[삽입]`.
- **삽입 커맨드(`editor/commands/insertRedmineBlock.ts`)**: `insertRedmineBlock(kind, payload)` → 현재 커서/선택 위치에 마크다운 삽입. **순수 함수로 MD 문자열 생성**(테스트 대상):
  - `meta` → GFM 표(상태/담당/우선순위/마감) + `> 🔗 redmine #{id}` 출처 인용.
  - `body` → 본문 그대로(CommonMark).
  - `comment` → `**{작성자}** {날짜}: {내용}` (인용/리스트 형식 결정 §11).
- **상태/훅**: `useRedmineImport`(검색·상세·로딩·삽입), `api/redmine.ts`(on401 전역 처리 재사용), mappers 단일 출처에 Redmine DTO→뷰모델 매핑 추가.
- **프로필 키 등록**: 보안 탭(SecurityTab) 또는 신규 "연동" 섹션 — 키 입력 → `PUT /me/redmine/token` → 검증 결과(성공 시 redmineLogin 표시)·삭제. 키 미구성(422) 시 안내.
- **관리자 설정 화면**: `redmine.enabled` 토글 + `redmine.base_url` 입력(기존 app_setting 편집 패턴 재사용).

## 9. 테스트 전략 (프로젝트 관례)

React 렌더/e2e 없음 → **결정 로직 순수 함수 추출 + vitest**, 실화면은 `/qa` browse. 백엔드는 JUnit(명명 DB 격리).

- **백엔드 유닛**: Redmine JSON → DTO 매핑(빈 journal 제외), 에러 매핑(401/404/5xx 분기), AES 라운드트립(SecretCipher 재사용 확인), 키 검증 흐름(`/users/current` mock).
- **백엔드 통합**: 키 미등록 409, redmine.enabled=0 404, 프록시가 세션 사용자 키를 사용하는지(타 사용자 키 격리), 감사 로깅.
- **프런트 유닛**: `splitDirection(width)` 임계 경계, `insertRedmineBlock` MD 생성(메타표/본문/댓글), DTO→뷰모델 매퍼, 툴바 버튼 노출 판정(enabled·tokenPresent).
- Redmine 호출은 테스트에서 mock(실서버 의존 금지).

## 10. 보안 고려사항

- **항상 호출자 본인 키** → Redmine ACL 그대로(단일 서비스 계정 금지 — ACL 우회 위험 제거).
- 키는 AES-256-GCM at-rest, 응답에 **평문 키 절대 미노출**(상태만: present/login/lastVerified).
- 프록시는 server·완전 인증 세션에서만(부분 인증/비인증 차단). 기존 `AuthFilter` 통과 경로.
- Redmine이 미열람 이슈를 404로 주는 동작을 신뢰(권한 누설 방지) — 별도 work-note 가공 없이 그대로 전달.
- 키 등록/삭제/이슈 임포트 `audit_log` 기록.
- 삽입된 본문은 일반 노트 콘텐츠로 기존 XSS 하드닝/마크다운 렌더 경로를 그대로 탄다(추가 입력 경로지만 신규 렌더러 아님).

## 11. 오픈 이슈 / 구현 시 결정

- 댓글 삽입 형식: 인용(`>`) vs 리스트 vs 헤더+문단 — **인용 + 작성자/날짜 헤더**로 제안(가독성).
- "내 이슈" 기본 필터: open 한정 vs 전체 — **open 한정 기본 + 상태 필터 토글**.
- 검색 페이지네이션: offset/limit 무한스크롤 vs 페이지 버튼 — 1차 **limit 25 + "더 보기"**.
- AES 키: `WORKNOTE_2FA_KEY` 공용(제안) vs 별도 `WORKNOTE_REDMINE_KEY` — 공용으로 단순화(§5). 운영에서 분리 원하면 env만 추가.
- 메타표 컬럼 구성(어떤 필드까지) — 상태·담당·우선순위·마감 4종 기본.

## 12. 환경변수 / 설정 요약

| 변수/설정 | 위치 | 설명 |
|---|---|---|
| `redmine.enabled` | app_setting | 기능 on/off(기본 0) |
| `redmine.base_url` | app_setting | 사내 Redmine 호스트(예: `http://redmine.intra`) |
| `WORKNOTE_2FA_KEY` | env | AES-256-GCM 키 공용(키 미설정 시 Redmine 키 저장 불가) |

## 13. 단계 (스코프)

- **1차(MVP)**: 관리자 설정 + 사용자 키 등록/검증 + 이슈 검색/내이슈 + 분할 패널 + 메타/본문/댓글 삽입.
- **2차**: 첨부 import(다운로드 중계 → 기존 파일첨부 인프라 임베드, [[worknote-attachments]]) · 드래그앤드롭 · 위키 페이지 · 슬래시 커맨드 진입 · "다시 열기" source-link.

---

관련 메모리: [[worknote-2fa-totp-plan]] [[worknote-backend-phase3-admin-api]] [[worknote-frontend-test-convention]] [[worknote-test-inmemory-isolation]] [[worknote-attachments]] [[worknote-frontend-integration]]
