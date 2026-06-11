# backend

work-note 서버. 단일 실행 jar (정적 frontend 서빙 + 노드 단위 REST API + SQLite). **1단계 + 2단계 코어(세션 인증 + 권한 엔진 + 감사 로그) 구현 완료** — 127 tests green, local/server 모드 jar 스모크 검증 완료.

## 스택 (확정)

- Java 21 + Spring Boot 3.5
- Gradle (wrapper 8.14, Groovy DSL)
- MyBatis (mybatis-spring-boot-starter 3.0.4)
- Flyway (vendor 디렉토리 전략: `db/migration/sqlite` — Oracle 전환 대비)
- sqlite-jdbc

## 명령어

```bash
cd backend
./gradlew test       # 테스트
./gradlew build      # 빌드 (build/libs/worknote-*.jar)
./gradlew bootRun    # 실행 (기본 DB: ./worknote.db, WORKNOTE_DB 환경변수로 변경)
```

## 모드 스위치 (`worknote.mode`)

| 모드 | 기동 | 동작 |
|------|------|------|
| `local` (기본) | `java -jar worknote-0.1.0.jar` | **1단계 동작 그대로** — 무인증, 모든 요청을 합성 `local` admin 주체로 처리, vault 감사 생략 |
| `server` | `WORKNOTE_MODE=server WORKNOTE_ADMIN_PASSWORD=... java -jar worknote-0.1.0.jar` | 세션 인증 강제(미인증 401) + 권한 엔진 enforcement + 감사 로그 |

- env: `WORKNOTE_MODE`(local\|server), `WORKNOTE_ADMIN_PASSWORD`(server 최초 기동 시 필수 — 없으면 fail-fast로 기동 거부)
- `worknote.mode`에 오타 등 미지의 값이 들어오면 기동 자체를 거부한다(WorknoteModeCheck — fail-open 방지)

## API

| 메서드 | 경로 | 설명 | 성공 코드 |
|--------|------|------|-----------|
| GET | `/api/tree` | 전체 트리 조회 | 200 |
| POST | `/api/nodes` | 노드 생성 | 201 |
| PATCH | `/api/nodes/{id}` | 노드 수정 (title/content/tags 등) | 204 |
| POST | `/api/nodes/{id}/move` | 노드 이동 | 204 |
| DELETE | `/api/nodes/{id}` | 휴지통으로 이동 (soft-delete) | 204 |
| GET | `/api/trash` | 휴지통 목록 | 200 |
| POST | `/api/trash/{id}/restore` | 휴지통 복구 | 204 |
| DELETE | `/api/trash/{id}` | 영구 삭제 (purge) | 204 |
| GET | `/api/health` | 헬스 체크 | 200 |

오류 응답: 404/409/422 → `{"error": "메시지"}`, 요청 검증 실패 → 400. server 모드 추가: 미인증 401, 권한 부족 403.

### 인증 API

| 메서드 | 경로 | 설명 | 응답 |
|--------|------|------|------|
| POST | `/api/auth/login` | `{"emp", "password"}` 로그인 | 200 me / 401(자격 불일치 — 동일 메시지) / 403(disabled·pending) |
| POST | `/api/auth/logout` | 세션 종료 | 204 |
| GET | `/api/auth/me` | 현재 주체 조회 | 200 `{id, emp, name, roleId, caps}` — local 모드는 합성 `local` admin |

### 권한 모델 적용 현황 (server 모드)

| 엔드포인트 | 요구 권한 |
|------------|-----------|
| GET `/api/tree` | read 필터 — 읽을 수 있는 노드만, 경로 연결용 조상 폴더는 스텁(이름만)으로 포함 |
| POST `/api/nodes` | `res.create` ∧ 부모 폴더 `edit` (루트 생성은 관리자 전용) |
| PATCH `/api/nodes/{id}` | 해당 노드 `edit` |
| POST `/api/nodes/{id}/move` | 원본 `edit` ∧ 대상 부모 `edit` (휴지통 노드는 이동 불가 — 복구가 유일한 출구) |
| DELETE `/api/nodes/{id}` | `res.delete` ∧ 해당 노드 `edit` |
| GET `/api/trash` | 본인 삭제분만 (관리자는 전체) |
| POST `/api/trash/{id}/restore` | 삭제자 본인 또는 관리자 (휴지통 루트만 — 고아 방지) |
| DELETE `/api/trash/{id}` | 관리자 전용 |

## 아키텍처

컨트롤러 → 서비스 → 매퍼(MyBatis) 3계층. 트리는 인접 리스트(`parent_id`)로 저장하고 하위 트리 해석은 재귀 CTE로 수행한다. 삭제는 soft-delete 휴지통 — 복구는 배치 의미론(같은 `deleted_at`을 가진 노드들이 한 단위로 복구)을 따른다.

## 설계 결정 기록

### 1단계

- **SQLite FK enforcement 의도적 OFF** — 무결성은 서비스 계층 검증이 담당 (parent 존재/타입/비삭제 검증, purge는 tag·acl·public_flag·space 선삭제 → node 삭제 순서 — id 재사용 시 옛 권한 부활 방지). Oracle 전환 시 FK가 statement-level로 검사되므로 현 쿼리 구조 그대로 안전.
- **Hikari pool 1** — SQLite 단일 라이터, SQLITE_BUSY 방지.
- **쓰기 API는 204** — 빈 200 바디는 프런트의 `fetch res.json()` 크래시를 유발.

### 2단계 코어

1. **`worknote.mode` 스위치** (기본 local) — 1단계 jar 사용성 보존. WorknoteModeCheck가 미지의 값에 fail-fast(오타로 인한 fail-open 방지).
2. **테이블명 `app_user`/`grant_type`/`audit_log`** — `user`/`type`/`audit`은 Oracle·PG 예약어라 회피.
3. **비밀번호 PBKDF2WithHmacSHA256 120k회 + 사용자별 salt** (별도 `user_credential` 테이블) — known-answer 테스트로 파라미터 핀 고정(의도치 않은 변경 감지).
4. **로그인 정보 은닉** — 401은 동일 메시지 + 계정 미존재 시 더미 verify로 타이밍 균등화, status(disabled 등) 검사는 비밀번호 검증 후, 로그인 성공 시 `changeSessionId()`로 세션 고정 방어.
5. **세션 = 서블릿 HttpSession 인메모리 30분** — Spring Session/Security 미사용. 폐쇄망 단일 서버라 외부 세션 스토어 불필요.
6. **AuthFilter 매 요청 DB 조회** — disabled 사용자 즉시 차단(세션 캐시 신뢰 안 함). allowlist = `/api/auth/login`, `/api/health`.
7. **최초 관리자 = AdminBootstrap** — `WORKNOTE_ADMIN_PASSWORD` 필수, 없으면 fail-fast. `@Transactional`로 user+credential 원자 생성.
8. **deny-sticky** — 스펙 §5.1 "deny 아래 재허용 없음"을 해석기에서 강제: 같은 주체의 조상 deny는 더 가까운 allow로 못 뒤집음 (스펙의 nearest-explicit 규칙과의 모순을 리뷰에서 발견 → secure-by-default로 해소).
9. **권한 합성** — 다중 주체(개인+팀) deny-우선 합집합, 유효 권한 = 역할 상한 ∩ ACL, public = nearest flag(read 전용), 관리자(= `admin.*` 5종 전부 보유)는 ACL 우회.
10. **트리/휴지통 정책** — read 필터 트리에 경로 연결용 폴더 스텁(이름만, folder 타입 한정), move는 휴지통 격리(복구가 유일한 출구), restore는 휴지통 루트만(고아 방지), purge는 관리자 전용.
11. **감사 = 사후 기록** — 컨트롤러에서 본 작업 성공 후 기록(작업 실패 시 감사 없음 — 의식적 트레이드오프). PATCH(디바운스 다발)는 제외, local 모드는 vault 감사 생략.

## Oracle 전환 체크리스트

1. `db/migration/oracle/` 디렉토리 추가 — V1 스키마의 TEXT → VARCHAR2/CLOB 매핑
2. 매퍼 XML의 `WITH RECURSIVE` → `WITH` (주석 표기된 4곳, `mappers/NodeMapper.xml`)
3. FK가 enforce되므로 데이터 정합 사전 검증
4. position 동시성(쓰기 경합) 대응 — 2단계 다중 사용자 시

## 다음 계획 이월 항목

- 공유 링크 (스펙 §6 — read 전용·만료·취소·로깅, `share_link` 테이블 V3 마이그레이션)
- 관리자 API — 사용자/역할/팀/스페이스/ACL/public_flag CRUD (public_flag는 upsert 필요), 가입 승인 플로우, audit 조회
- 프런트 연동 — 로그인 페이지, admin 페이지, 403 처리, me 기반 UI 가드
- 30일 자동 purge 스케줄러
- 이동 시 노출 변경 경고 (스펙 §7)
- `/tree` findActive 2회 조회 최적화
- RoleCaps 캐시 (역할 수정 API와 함께 도입)
- fire-and-forget 동기화 충돌 처리 (1단계 이월)
- useVault 언로드 플러시 `sendBeacon` 일반화 (1단계 이월)

## 배포 (단일 실행 jar)

jar 하나에 frontend 정적 빌드 + REST API + SQLite가 모두 들어간다. Gradle은 pnpm을 호출하지 않으므로 **jar 빌드 전 frontend 빌드를 먼저** 해야 한다.

### 빌드 순서

```bash
cd frontend && pnpm build        # → frontend/dist/ 생성
cd ../backend && ./gradlew bootJar   # dist를 classpath:/static으로 포함 → build/libs/worknote-0.1.0.jar
```

### 실행

```bash
java -jar build/libs/worknote-0.1.0.jar                                            # local 모드 (무인증)
WORKNOTE_MODE=server WORKNOTE_ADMIN_PASSWORD=... java -jar build/libs/worknote-0.1.0.jar   # server 모드
# http://localhost:8080/           → index.html (에디터)
# http://localhost:8080/login.html, /admin.html
# http://localhost:8080/api/*     → REST API
```

### `WORKNOTE_DB` 환경변수

SQLite 파일 경로. 미지정 시 `./worknote.db` (실행 cwd 기준).

```bash
WORKNOTE_DB=/var/lib/worknote/worknote.db java -jar worknote-0.1.0.jar
```

> **운영에선 절대경로 권장** — 상대경로는 실행 위치(cwd)에 따라 DB 파일이 달라진다.

### 폐쇄망 노트

- Gradle wrapper는 첫 빌드 시 `services.gradle.org`에서 배포판을 내려받는다 → 폐쇄망에서는 **사전 캐시**(`~/.gradle/wrapper/dists` 복사) 또는 `gradle-wrapper.properties`의 `distributionUrl`을 **사내 미러**로 변경.
- 의존성도 동일하게 사전 캐시(`~/.gradle/caches`) 또는 사내 Maven 미러 필요.
- frontend는 CDN 의존 0 — `pnpm install` 오프라인 스토어만 준비되면 됨.

## 범위

- 2단계(사내 서버 공용)의 권한 엔진 + vault 영속화.
- 설계 근거: [`../docs/superpowers/specs/2026-06-10-worknote-권한-디렉토리-design.md`](../docs/superpowers/specs/2026-06-10-worknote-권한-디렉토리-design.md)
  - `node`/`tag` 스키마(1·2단계 공통) + 권한 테이블(2단계)
  - 해석기: nearest-explicit + deny-우선 합집합 (재귀 CTE)

> 1단계(개인 PC·단일 사용자)는 local 모드로 권한 엔진 없이 SQLite 영속화만. 2단계 코어(인증+권한+감사)는 server 모드에서 enforce — 공유 링크·관리자 API·프런트 연동은 다음 계획.
