# work-note frontend

폐쇄망 사내 마크다운 웹에디터 프런트엔드.

## 스택

| 항목 | 버전 |
|------|------|
| Vite | 6 |
| TypeScript | 5 |
| React | 18 |
| CodeMirror | 6 |
| 패키지 매니저 | pnpm |

## 명령어

```bash
pnpm install   # 의존성 설치
pnpm dev       # 개발 서버 (localhost:5173)
pnpm build     # dist/ 빌드
pnpm test      # Vitest 단위 테스트
pnpm preview   # dist/ 로컬 미리보기
```

## 폐쇄망 배포

```bash
pnpm build
# dist/ 디렉토리를 정적 서버에 복사
```

외부 CDN 의존 없음. `vite.config.ts`의 `base: "./"` — 서브경로 배포 가능.

## 디렉토리 구조

```
src/
  components/   UI 컴포넌트 (Sidebar, Editor, Outline, SearchModal 등)
  state/        커스텀 훅 + vault reducer (useReducer 기반 단일 진실 공급원)
  storage/      VaultRepository 인터페이스 + LocalStorageRepository 구현
  lib/          markdown 렌더링, 유틸리티
  editor/       CodeMirror 6 Live Preview 래퍼 (cm.ts)
  commands/     커맨드 패턴 핸들러
  admin/        관리자 페이지 컴포넌트
  login/        로그인 페이지 컴포넌트
  styles/       CSS 변수, 테마, 글로벌 스타일
```

## 아키텍처 메모

- **vault reducer**: `state/` 내 단일 진실 공급원. 모든 노트 CRUD는 reducer를 통해 처리.
- **VaultRepository 스왑 지점**: 현재 localStorage 구현(`LocalStorageRepository`). 1단계 SQLite, 2단계 HTTP API 구현으로 교체 예정 — 인터페이스(`load`/`save`, async)만 맞추면 됨. HTTP 구현 시 `useVault`의 언로드 플러시(`saveSync` instanceof 분기)를 `sendBeacon`/keepalive로 일반화할 것.
- **markdown 렌더링**: `marked.parse`를 동기(string) 계약으로 사용 — `marked.use({ walkTokens: async })` 류 확장 금지 (`lib/markdown.ts` 참조).
- **디자인 원본**: `../docs/design-handoff/prototype/` (비교 서버: `python3 -m http.server`)

## 알려진 주의사항

- `NoteNode`는 shallow-copy 구조. `updateNote` 내부에서 배열 in-place 변이(`push`, `splice` 등) 금지 — 반드시 새 배열을 할당해야 React 리렌더가 올바르게 동작함.
