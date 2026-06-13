# backend

work-note 서버. 단일 실행 jar (정적 frontend 서빙 + 노드 단위 REST API + SQLite). **1단계 + 2단계 코어(세션 인증 + 권한 엔진 + 감사 로그) + 3단계 관리자 API(가입 승인·사용자/역할/팀/스페이스/ACL/public/감사 조회) + 5단계(30일 purge 스케줄러 + 공유 링크 §6) + 6단계(이동 노출 변경 경고 §7) + 4단계 이월 마감(본인 비밀번호 변경 API·401 편집 유실 복구·백엔드 다운 차단 배너) + 후속 마감(본인 프로필 name/email 수정 API·트리 드래그앤드롭 이동·비밀번호 최소 길이 10자 통일) 구현 완료** — 268 tests green, local/server 모드 jar 스모크 검증 완료. 프런트 연동(4단계: 로그인·가입 + admin 8스크린 실 API 배선 + 공유 모달·share.html·admin 공유 링크 화면 + 이동 폴더 피커·노출 경고 모달 + 본인 비밀번호 변경·편집 유실 복구·다운 차단 화면 + 본인 프로필 수정·DnD 이동) 완료.

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
| POST | `/api/nodes/{id}/move` | 노드 이동 (노출 변경 시 감사 target에 접미사) | 204 |
| GET | `/api/nodes/{id}/move-preview?parentId=` | 이동 시 노출(접근 집합) 변경 미리보기 | 200 |
| DELETE | `/api/nodes/{id}` | 휴지통으로 이동 (soft-delete) | 204 |
| GET | `/api/trash` | 휴지통 목록 | 200 |
| POST | `/api/trash/{id}/restore` | 휴지통 복구 | 204 |
| DELETE | `/api/trash/{id}` | 영구 삭제 (purge) | 204 |
| GET | `/api/health` | 헬스 체크 | 200 |

휴지통은 30일 보존 후 자동 purge — 기동 60초 후 1회 + 24시간 간격, `WORKNOTE_PURGE_RETENTION_DAYS`(기본 30, 0 이하 = 끔). 자동 purge 감사는 `who="system"`.

### 공유 링크 API (스펙 §6 — deny를 넘는 유일한 read 예외, 만료·취소·로깅)

| 메서드 | 경로 | 권한 | 성공 코드 |
|--------|------|------|-----------|
| POST | `/api/nodes/{id}/share` | `res.share ∧ read(N)`, 노트만 | 201 `{id, token, expiresAt}` — body `{days?=7, maxViews?, pinEmps?}` |
| GET | `/api/nodes/{id}/shares` | 동일 | 200 활성 링크 목록(관리자=전체, 그 외 본인 생성분) |
| GET | `/api/share/{token}` | 인증만 (read 권한 불요) | 200 `{name, content, updatedAt}` — 무효 사유는 전부 404 단일 |
| DELETE | `/api/shares/{id}` | 생성자 본인 ∨ 관리자 | 204 (재취소 409) |
| GET | `/api/admin/shares` | 관리자 | 200 활성 링크 전체(+nodeName·suspended) |

감사 act 3종 `share.create/view/revoke` — target은 `{linkId} -> {nodeId}`, **token 원문 비기록**. 휴지통 노드의 링크는 suspend(접근 시 판정), restore로 부활, purge 시 영구 삭제.

오류 응답: 404/409/422 → `{"error": "메시지"}`, 요청 검증 실패 → 400. server 모드 추가: 미인증 401, 권한 부족 403.

### 인증 API

| 메서드 | 경로 | 설명 | 응답 |
|--------|------|------|------|
| POST | `/api/auth/login` | `{"emp", "password"}` 로그인 | 200 me / 401(자격 불일치 — 동일 메시지) / 403(disabled·pending) |
| POST | `/api/auth/signup` | `{"emp", "name", "password"[, "email"]}` 가입 신청 — pending visitor 생성(무세션, allowlist) | 201 `{id, status}` / 409(사번 중복) / 400(비밀번호 10자 미만) |
| POST | `/api/auth/logout` | 세션 종료 | 204 |
| POST | `/api/auth/change-password` | `{"currentPassword", "newPassword"}` 본인 비밀번호 변경 — 세션 salt 갱신(본인 세션 유지·타 기기 세션 무효) + 감사 `auth.password.change` | 204 / 422(현재 비번 불일치·새 비번 10자 미만·동일) / 401(미인증) / 403(local 모드) |
| POST | `/api/auth/update-profile` | `{"name", "email"}` 본인 프로필 수정 — email 공백→null 정규화 + 감사 `auth.profile.update` | 200 me / 400(name 빈값) / 401(미인증) / 403(local 모드) |
| GET | `/api/auth/me` | 현재 주체 조회 | 200 `{id, emp, name, email, roleId, caps}` — local 모드는 합성 `local` admin(email=null) |

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

### 관리자 API (server 모드, 관리자 전용 — local 모드는 무가드 통과)

모든 엔드포인트 첫 줄 `AdminGuard.requireAdmin` — local(user=null) bypass, server 미인증 401·비관리자 403. 변이는 성공 후 감사 기록(`user.create`/`acl.set` 등 dot 명명), 조회는 기록 안 함.

**사용자** (`/api/admin/users`)

| 메서드 | 경로 | 설명 | 성공 |
|--------|------|------|------|
| GET | `/api/admin/users` | 전체 목록 (emp 정렬) | 200 |
| POST | `/api/admin/users` | 관리자 직접 생성 (active) | 201 |
| PATCH | `/api/admin/users/{id}` | name/email/roleId/status 부분 수정 — self 역할·상태 변경 422, 마지막 활성 admin 강등·비활성 422 | 200 |
| POST | `/api/admin/users/{id}/approve` | pending→active (비pending 409) | 200 |
| POST | `/api/admin/users/{id}/reset-password` | 새 salt+hash — 기존 세션 즉시 무효화 | 204 |

**역할** (`/api/admin/roles`)

| 메서드 | 경로 | 설명 | 성공 |
|--------|------|------|------|
| GET | `/api/admin/roles` | 목록 (`{id, name, system, caps, userCount}`) | 200 |
| POST | `/api/admin/roles` | 생성 — caps는 KNOWN_CAPS 화이트리스트(미지 cap 422) | 201 |
| PATCH | `/api/admin/roles/{id}` | name/caps 수정 — 시스템 역할 422, admin 역할 caps 락아웃 가드 | 200 |
| DELETE | `/api/admin/roles/{id}` | 삭제 — 시스템 역할 422, 사용 중 409 | 204 |

**팀** (`/api/admin/teams`)

| 메서드 | 경로 | 설명 | 성공 |
|--------|------|------|------|
| GET | `/api/admin/teams` | 목록 (멤버 포함) | 200 |
| POST | `/api/admin/teams` | 생성 | 201 |
| PATCH | `/api/admin/teams/{id}` | 이름 변경 | 204 |
| DELETE | `/api/admin/teams/{id}` | 삭제 — 소유 스페이스 있으면 409, 멤버십+해당 팀 ACL 정리 후 삭제 | 204 |
| POST | `/api/admin/teams/{id}/members` | 멤버 추가 (`{userId}`) | 204 |
| DELETE | `/api/admin/teams/{id}/members/{userId}` | 멤버 제거 | 204 |

**스페이스** (`/api/admin/spaces`)

| 메서드 | 경로 | 설명 | 성공 |
|--------|------|------|------|
| GET | `/api/admin/spaces` | 목록 | 200 |
| PUT | `/api/admin/spaces/{nodeId}` | 지정/교체 (`{teamId?}`) — 최상위 활성 폴더만, 소유 팀 edit 자동 grant, 교체 시 구 팀 grant 잔존을 감사 target에 부기 | 204 |
| DELETE | `/api/admin/spaces/{nodeId}` | 해제 | 204 |

**ACL / public** (`/api/admin`)

| 메서드 | 경로 | 설명 | 성공 |
|--------|------|------|------|
| GET | `/api/admin/acl` | 전체 ACL 목록 | 200 |
| GET | `/api/admin/nodes/{id}/acl` | 노드 ACL 조회 | 200 |
| GET | `/api/admin/public` | 전체 public 플래그 목록 (`[{nodeId, mode}]`) — 조회라 감사 기록 없음 | 200 |
| PUT | `/api/admin/nodes/{id}/acl` | **replace-all** (`{entries: [{principalType, principalId, grantType}]}`) — 주체 존재 검증, 스페이스 소유 팀 grant 부재 시 감사 부기 | 204 |
| PUT | `/api/admin/nodes/{id}/public` | public_flag upsert (`{mode: "public"\|"exclude"}`) | 204 |
| DELETE | `/api/admin/nodes/{id}/public` | public_flag 제거 | 204 |

**감사** (`/api/admin/audit`)

| 메서드 | 경로 | 설명 | 성공 |
|--------|------|------|------|
| GET | `/api/admin/audit` | `?who=&act=&from=&to=&limit=&offset=` — who/act 정확 일치, from/to는 ISO 사전순, limit 기본 50·최대 200. 응답 `{total, rows}` | 200 |

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

### 3단계: 관리자 API

1. **락아웃 방지 2중 규칙** — ① 자기 자신의 role/status 변경 금지(422) ② 마지막 활성 관리자 강등·비활성 금지(422). 여기에 역할 축 가드 추가: admin caps를 가진 역할에서 admin caps를 제거하는 수정은 그 역할 밖의 활성 관리자가 있어야 허용 — 시스템 역할 보호를 우회하는 커스텀 admin 역할 강등 경로 차단. 폐쇄망은 관리자 0명이 되면 복구 수단이 없다.
2. **비밀번호 리셋 = salt 교체** — AuthFilter가 매 요청 세션의 salt와 DB salt를 비교하므로 리셋 즉시 기존 세션이 전부 무효화된다(분실·탈취 대응).
3. **caps 화이트리스트 (fail-closed)** — RoleCaps는 DB JSON을 신뢰하므로 쓰기 시점에 KNOWN_CAPS 검증(미지 cap 422). 오타 caps가 들어가면 fail-open/lock 둘 다 가능했던 지뢰 제거.
4. **ACL 쓰기 = 노드 단위 replace-all** — 관리 UI의 "노드 선택→편집→저장" 모델과 1:1. 동시 편집은 마지막 저장 승리 — `acl.set` 감사로 이전 상태 재구성 가능.
5. **팀 삭제 시 멤버십+해당 팀 ACL 정리** — ACL 잔여 행은 팀 id 재사용 시 권한 부활(purge에서 확립한 원칙과 동일). 소유 스페이스가 있으면 409로 차단.
6. **스페이스/ACL 변경 시 잔존·부재를 감사 target에 부기** — 스페이스 교체 시 구 팀 grant 잔존, ACL replace 시 스페이스 소유 팀 grant 부재를 자동 회수/보충하지 않고 감사에 가시화만 한다(자동 회수는 의도된 권한을 깨뜨릴 수 있음 — 보수적 동작).
7. **public 폴더 하위 새 노트 자동 exclude** (스펙 §7) — VaultService.create에서 nearest public flag가 public이면 명시 exclude 엔트리 삽입 — 새 노트가 의도치 않게 공개되는 것 방지.
8. **UNIQUE race는 DuplicateKeyException→409** — 사전 존재 검사와 INSERT 사이 race를 DB 제약이 받치고, 핸들러가 409로 변환.

### 후속 마감 (프로필 수정·DnD 이동·비번 정책)

1. **본인 프로필 수정 = change-password 패턴 재사용** — `update-profile`은 CURRENT_USER로 본인 식별, 역할·상태·credential 불변(name/email만), 오류는 전부 422/400(절대 401 금지 — 프런트 on401 로그아웃 유발). 응답으로 `MeResponse`(email 추가)를 돌려줘 프런트가 세션 me를 즉시 갱신. `MeResponse` 6인자화는 login·me·update-profile 세 경로의 단일 `toMe()`를 통해 일관 — email은 항상 인증된 본인 세션 한정 노출.
2. **비밀번호 최소 길이 = `PasswordPolicy.MIN_LENGTH`(10) 단일 출처** — 가입·관리자 생성·초기화 DTO `@Size(min=PasswordPolicy.MIN_LENGTH)`(컴파일 상수) + `changePassword`가 모두 참조. 프런트도 `MIN_PASSWORD_LENGTH`(10)로 대응. @Size 위반=400 / changePassword 위반=422 비대칭은 기존 동작 유지.
3. **DnD 이동은 1차 UX 필터** — 프런트 `canDropOn`(자기/자손·비폴더 타깃·동일 부모 차단)은 드롭 가능성 사전 판정일 뿐, 최종 검증·권한은 백엔드 `move-preview`/`move`(`validateMove`+`guard.requireMove`)가 수행. 노출 경고 UI(`MoveWarnContent`)는 컨텍스트 메뉴·DnD 두 경로가 공유.

## Oracle 전환 체크리스트

1. `db/migration/oracle/` 디렉토리 추가 — V1 스키마의 TEXT → VARCHAR2/CLOB 매핑
2. 매퍼 XML의 `WITH RECURSIVE` → `WITH` (주석 표기된 4곳, `mappers/NodeMapper.xml`)
3. FK가 enforce되므로 데이터 정합 사전 검증
4. position 동시성(쓰기 경합) 대응 — 2단계 다중 사용자 시

## 다음 계획 이월 항목

- 폴더 이동 시 서브트리 노드별 개별 override 열거 (현재 폴더 레벨 노출 신호만 — 6단계 §7 구현 범위 결정 M7)
- pin 사번 존재 검증 (현재 미검증 — 오타 시 아무도 못 여는 링크, fail-closed라 무해. 5단계 리뷰에서 식별)
- 만료·취소 공유 링크 행 정리 배치 (현재 영구 보존 — 감사 재구성 우선. 5단계 리뷰에서 식별)
- 실패한 공유 열람 시도(404) 감사 기록 — 프로빙 탐지용 (5단계 리뷰에서 식별, 스펙 §6은 성공 열람만 명시)
- `/tree` findActive 2회 조회 최적화
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

> 1단계(개인 PC·단일 사용자)는 local 모드로 권한 엔진 없이 SQLite 영속화만. 2단계 코어(인증+권한+감사)와 3단계 관리자 API는 server 모드에서 enforce, 프런트 연동(4단계)·공유 링크+purge 스케줄러(5단계)·이동 노출 변경 경고(6단계 §7)까지 완료.
