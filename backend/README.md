# backend

work-note 서버. 1단계: 단일 실행 jar (정적 frontend 서빙 + 노드 단위 REST API + SQLite).

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
