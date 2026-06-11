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

## 범위

- 2단계(사내 서버 공용)의 권한 엔진 + vault 영속화.
- 설계 근거: [`../docs/superpowers/specs/2026-06-10-worknote-권한-디렉토리-design.md`](../docs/superpowers/specs/2026-06-10-worknote-권한-디렉토리-design.md)
  - `node`/`tag` 스키마(1·2단계 공통) + 권한 테이블(2단계)
  - 해석기: nearest-explicit + deny-우선 합집합 (재귀 CTE)

> 1단계(개인 PC·단일 사용자)는 권한 엔진 없이 SQLite 영속화만. 2단계 전환 시 권한 테이블 추가.
