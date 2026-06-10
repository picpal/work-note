# backend (예정)

work-note 서버 — **아직 미구현**. 프런트 시안 검증 후 계획 수립 예정.

## 스택 (확정)
- Java 21
- Gradle
- SQLite

## 범위
- 2단계(사내 서버 공용)의 권한 엔진 + vault 영속화.
- 설계 근거: [`../docs/superpowers/specs/2026-06-10-worknote-권한-디렉토리-design.md`](../docs/superpowers/specs/2026-06-10-worknote-권한-디렉토리-design.md)
  - `node`/`tag` 스키마(1·2단계 공통) + 권한 테이블(2단계)
  - 해석기: nearest-explicit + deny-우선 합집합 (재귀 CTE)

> 1단계(개인 PC·단일 사용자)는 권한 엔진 없이 SQLite 영속화만. 2단계 전환 시 권한 테이블 추가.
