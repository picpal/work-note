# backend

work-note 서버. 1단계: 단일 실행 jar (정적 frontend 서빙 + 노드 단위 REST API + SQLite). **1단계 구현 완료** — 31 tests green, E2E 검증 완료.

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

오류 응답: 404/409/422 → `{"error": "메시지"}`, 요청 검증 실패 → 400.

## 아키텍처

컨트롤러 → 서비스 → 매퍼(MyBatis) 3계층. 트리는 인접 리스트(`parent_id`)로 저장하고 하위 트리 해석은 재귀 CTE로 수행한다. 삭제는 soft-delete 휴지통 — 복구는 배치 의미론(같은 `deleted_at`을 가진 노드들이 한 단위로 복구)을 따른다.

## 설계 결정 기록

- **SQLite FK enforcement 의도적 OFF** — 무결성은 서비스 계층 검증이 담당 (parent 존재/타입/비삭제 검증, purge는 tag 선삭제 → node 삭제 순서). Oracle 전환 시 FK가 statement-level로 검사되므로 현 쿼리 구조 그대로 안전.
- **Hikari pool 1** — SQLite 단일 라이터, SQLITE_BUSY 방지.
- **쓰기 API는 204** — 빈 200 바디는 프런트의 `fetch res.json()` 크래시를 유발.

## Oracle 전환 체크리스트

1. `db/migration/oracle/` 디렉토리 추가 — V1 스키마의 TEXT → VARCHAR2/CLOB 매핑
2. 매퍼 XML의 `WITH RECURSIVE` → `WITH` (주석 표기된 4곳, `mappers/NodeMapper.xml`)
3. FK가 enforce되므로 데이터 정합 사전 검증
4. position 동시성(쓰기 경합) 대응 — 2단계 다중 사용자 시

## 2단계 이월 항목

- 권한 엔진 (deny-우선 합집합)
- 30일 자동 purge 스케줄러
- fire-and-forget 동기화 충돌 처리
- useVault 언로드 플러시 `sendBeacon` 일반화

## 배포 (단일 실행 jar)

jar 하나에 frontend 정적 빌드 + REST API + SQLite가 모두 들어간다. Gradle은 pnpm을 호출하지 않으므로 **jar 빌드 전 frontend 빌드를 먼저** 해야 한다.

### 빌드 순서

```bash
cd frontend && pnpm build        # → frontend/dist/ 생성
cd ../backend && ./gradlew bootJar   # dist를 classpath:/static으로 포함 → build/libs/worknote-0.1.0.jar
```

### 실행

```bash
java -jar build/libs/worknote-0.1.0.jar
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

> 1단계(개인 PC·단일 사용자)는 권한 엔진 없이 SQLite 영속화만. 2단계 전환 시 권한 테이블 추가.
