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

## 스토리지 모드 (HTTP / localStorage)

`VITE_STORAGE` 환경변수로 스위치:

| 모드 | 값 | 사용 시점 |
|------|----|----------|
| local | (기본) | `pnpm dev` — localStorage 단독 동작 |
| http | `VITE_STORAGE=http` | 프로덕션 빌드 (`.env.production`에 설정 — 항상 HTTP 모드) |

HTTP 모드로 dev 서버를 띄우려면 백엔드가 먼저 떠 있어야 한다 (vite proxy `/api` → `:8080`):

```bash
VITE_STORAGE=http pnpm dev
```

### 동기화 동작 (HTTP 모드)

- reducer 액션(생성/삭제/이동 등) → API 즉시 호출
- content/tags/title 변경 → 노트별 1.5초 디바운스 PATCH
- 실패 시 토스트 표시 + 낙관적 UI 유지
- 빈 서버면 SEED 자동 부트스트랩 (409 멱등)

## 디렉토리 구조

```
src/
  components/   UI 컴포넌트 (Sidebar, Editor, Outline, SearchModal 등)
  state/        커스텀 훅 + vault reducer (useReducer 기반 단일 진실 공급원)
  storage/      VaultRepository 인터페이스 + LocalStorage/Http 구현
  lib/          markdown 렌더링, 유틸리티
  editor/       CodeMirror 6 Live Preview 래퍼 (cm.ts)
  commands/     커맨드 패턴 핸들러
  admin/        관리자 페이지 컴포넌트
  login/        로그인 페이지 컴포넌트
  styles/       CSS 변수, 테마, 글로벌 스타일
```

## 아키텍처 메모

- **vault reducer**: `state/` 내 단일 진실 공급원. 모든 노트 CRUD는 reducer를 통해 처리.
- **VaultRepository 스왑 지점**: `LocalStorageRepository`(local 모드)와 `HttpVaultRepository`(http 모드) 두 구현. `HttpVaultRepository`는 `load`만 담당하고 `save`는 no-op — 쓰기는 `useVaultSync`(reducer 액션 → API 동기화 데코레이터 훅)가 담당. `useVault`의 언로드 플러시(`saveSync` instanceof 분기)를 `sendBeacon`/keepalive로 일반화하는 것은 2단계 이월.
- **markdown 렌더링**: `marked.parse`를 동기(string) 계약으로 사용 — `marked.use({ walkTokens: async })` 류 확장 금지 (`lib/markdown.ts` 참조).
- **디자인 원본**: `../docs/design-handoff/prototype/` (비교 서버: `python3 -m http.server`)

## 알려진 주의사항

- `NoteNode`는 shallow-copy 구조. `updateNote` 내부에서 배열 in-place 변이(`push`, `splice` 등) 금지 — 반드시 새 배열을 할당해야 React 리렌더가 올바르게 동작함.
