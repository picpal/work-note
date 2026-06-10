# work-note — 프로젝트 개요

폐쇄망 사내 마크다운 에디터. 1단계: 개인 PC SQLite 로컬 사용 → 2단계: 서버 공용(3~4팀 규모).

## 구조

```
work-note/
  frontend/                    Vite 6 + TypeScript + React 18 (구현 완료)
  backend/                     Java 21 + Gradle + SQLite (예정)
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

## 명령어 (frontend)

```bash
cd frontend
pnpm install
pnpm dev       # 개발 서버
pnpm build     # dist/ 정적 빌드 (CDN 의존 0)
pnpm test      # Vitest
pnpm preview
```

## 핵심 설계 결정

### 권한 모델

- 유효 권한 = 역할 상한(Role ceiling) ∩ ACL 범위(Explicit allow)
- deny는 절대 우선 — 공유 링크 접근만 예외(링크 소지자 임시 read)
- 팀 = 그룹(멤버 집합), 단일 vault + 팀 스페이스(폴더 prefix)
- 상세: `docs/superpowers/specs/2026-06-10-worknote-권한-디렉토리-design.md`

### 스토리지 스왑 지점

`frontend/src/storage/VaultRepository` 인터페이스 — 1단계 IndexedDB, 2단계 HTTP API로 교체.

### 디렉토리 설계

폴더 트리는 경로 문자열(path prefix)로 관리. 이동 = path rename. 상세 스펙 동일 문서 참조.
