# work-note — 프로젝트 개요

폐쇄망 사내 마크다운 에디터. 1단계: 개인 PC SQLite 로컬 사용 → 2단계: 서버 공용(3~4팀 규모).

## 구조

```
work-note/
  frontend/                    Vite 6 + TypeScript + React 18 (구현 완료)
  backend/                     Java 21 + Spring Boot 3.5 + MyBatis + Flyway + SQLite
                               1단계 + 2단계 코어(세션 인증 + 권한 엔진) 구현 완료 — worknote.mode로 스위치(기본 local=무인증)
                               server 모드: WORKNOTE_MODE=server WORKNOTE_ADMIN_PASSWORD=... java -jar ...
                               공유 링크·관리자 API·프런트 연동은 다음 계획
  docs/
    superpowers/
      specs/                   권한·디렉토리 설계 스펙 문서
      plans/                   구현 계획 문서
    design-handoff/            디자인 원본, 채팅 기록, 스크린샷
```

## 프런트엔드 원칙

프로토타입(`docs/design-handoff/prototype/CLAUDE.md`) 계승:

- 컴포넌트화: 기능 단위로 분리, props 인터페이스 명시
- reducer 패턴: 상태 변경은 vault reducer 단일 경로
- 커스텀 훅: 컴포넌트에서 로직 분리
- 커맨드 패턴: 에디터 툴바 액션은 `commands/`에 집중
- 모노톤 디자인: Pretendard(UI) + D2Coding(코드), CSS 변수 기반

## 명령어

```bash
# frontend
cd frontend
pnpm install
pnpm dev       # 개발 서버 (localStorage 모드. HTTP 모드: VITE_STORAGE=http pnpm dev — 백엔드 기동 필요)
pnpm build     # dist/ 정적 빌드 (CDN 의존 0, .env.production → 항상 HTTP 모드)
pnpm test      # Vitest
pnpm preview

# backend
cd backend
./gradlew test       # 테스트
./gradlew bootJar    # 단일 jar (frontend dist를 classpath:/static으로 포함 — pnpm build 선행 필요)
java -jar build/libs/worknote-0.1.0.jar   # 실행 (WORKNOTE_DB 환경변수로 DB 경로 지정, 기본 local 모드=무인증)
WORKNOTE_MODE=server WORKNOTE_ADMIN_PASSWORD=... java -jar build/libs/worknote-0.1.0.jar   # server 모드 (인증+권한 enforce)
```

## 핵심 설계 결정

### 권한 모델

- 유효 권한 = 역할 상한(능력) ∩ ACL 범위(리소스). 역할은 enforce되는 권한 집합, ACL은 폴더 상속 + 카브아웃 deny
- deny는 절대 우선 (개인 grant도 팀 deny를 못 뚫음) — 유일 예외는 만료·취소·로깅되는 공유 링크(read 전용)
- 다중 주체(개인+팀) 해석 = deny-우선 합집합. public = 폴더 cascade + 노트 exclude, 새 노트 기본 제외
- **deny-sticky**: 같은 주체의 조상 deny는 더 가까운 allow로 못 뒤집음 — 스펙 §5.1 "deny 아래 재허용 없음"을 해석기에서 강제(2단계 구현 확정)
- 팀 = 그룹(역할 아님). 단일 vault + 최상위 팀 스페이스(1급 메타데이터, 소유 팀 자동 grant)
- 상세: `docs/superpowers/specs/2026-06-10-worknote-권한-디렉토리-design.md`

### 스토리지 스왑 지점

`frontend/src/storage/VaultRepository` 인터페이스 — **1단계 완료**: HTTP API + SQLite 동작 중. `VITE_STORAGE`로 모드 스위치(local=localStorage, http=백엔드 API). 쓰기 동기화는 `useVaultSync`(액션→API 즉시, content/tags/title은 1.5초 디바운스 PATCH).

### 디렉토리 설계

트리는 **인접 리스트(`parent_id`) + 안정적 id** 로 관리 — path 문자열 아님. 이름은 라벨일 뿐(rename은 권한 무영향), 권한 상속 해석은 조상 walk(재귀 CTE), 이동은 위치 따름 + 가드(노출 변경 경고·감사). 삭제는 휴지통(soft-delete, 30일 purge). 상세 스펙 동일 문서 참조.
