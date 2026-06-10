# Frontend Vite+TypeScript 마이그레이션 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 디자인 핸드오프 프로토타입(전역 40개·Babel standalone·CDN)을 Vite + pnpm + TypeScript ESM 멀티페이지 앱으로 픽셀 동일하게 이주하고, Repository 패턴·구조 공유·테스트로 유지보수성을 확보한다.

**Architecture:** 3-entry MPA(index/login/admin) Vite 빌드. 기존 디자인 패턴(vault reducer, 커스텀 훅, export 커맨드)은 그대로 ESM 모듈로 이식. CDN 의존 전부 npm 로컬 번들로 교체(폐쇄망 `dist/` 출력). tweaks-panel(디자인 툴 잔재)은 삭제하고 동일 동작의 useSettings + SettingsModal로 대체. Phase 2에서 storage를 VaultRepository 인터페이스 뒤로 분리(추후 SQLite/HTTP 스왑 지점)하고 트리 불변 연산을 전체 deep-clone → 경로 복사(구조 공유)로 교체.

**Tech Stack:** Vite 6 · TypeScript 5 · React 18.3.1 · pnpm · vitest · marked 12 · highlight.js 11 · mermaid 10 · CodeMirror 6 (버전은 프로토타입 importmap과 동일하게 고정)

**원천 자료:** 프로토타입 = `docs/design-handoff/prototype/` (Task 0에서 `frontend/`로부터 이동). 모든 포팅 태스크는 이 경로의 파일을 원본으로 참조한다. 포팅 원칙 = **픽셀 동일·동작 동일**, 로직 재작성 금지, 명시된 변환 규칙만 적용.

**공통 변환 규칙 (모든 포팅 태스크에 적용):**
1. 파일 최외곽 IIFE `(function () { ... })()` 제거.
2. `window.X = ...` / `Object.assign(window, {...})` 등록 제거 → `export` 로 교체.
3. `window.Y` 소비 → 해당 모듈 `import` 로 교체 (매핑은 각 태스크의 표 참조).
4. `const { useState, ... } = React;` → `import { useState, ... } from "react";`
5. `React.createElement` 호출 스타일은 **그대로 유지** (JSX 문법 재작성 금지 — 기계적 이주 원칙).
6. 파라미터·상태에 Task 2의 타입(`VaultNode`/`NoteNode`/`FolderNode` 등)을 부여하되, 타입 때문에 로직을 바꾸지 않는다. 외부 라이브러리 경계에서 불가피하면 `as` 단언 허용.
7. 함수 본문 로직·CSS 클래스명·문자열(한국어 토스트 등)은 변경 금지.

---

## Phase 1 — 기계적 이주 (픽셀 동일)

### Task 0: git init + 프로토타입 격리

**Files:**
- Create: `.gitignore`
- Move: `frontend/*` → `docs/design-handoff/prototype/`

- [ ] **Step 1: git init + 현재 상태 첫 커밋**

```bash
cd /Users/picpal/Desktop/workspace/work-note
git init -b main
cat > .gitignore <<'EOF'
node_modules/
dist/
*.log
.DS_Store
EOF
git add -A
git commit -m "chore: initial import — design handoff prototype, specs, backend placeholder"
```

- [ ] **Step 2: 프로토타입을 docs/design-handoff/prototype/ 로 이동**

```bash
mkdir -p docs/design-handoff/prototype
git mv frontend/* docs/design-handoff/prototype/
git commit -m "chore: move prototype to docs/design-handoff (frontend/ becomes Vite app)"
```

- [ ] **Step 3: 비교용 프로토타입 서버 재기동**

기존 `frontend/`를 서빙하던 백그라운드 서버(task `bdw6ckse4`)를 중지하고, 새 위치에서 8001 포트로 재기동:
```bash
cd docs/design-handoff/prototype && python3 -m http.server 8001 --bind 127.0.0.1 &
```
Expected: `curl -s -o /dev/null -w "%{http_code}" http://127.0.0.1:8001/index.html` → `200`

### Task 1: Vite 스캐폴드 (3-entry MPA)

**Files:**
- Create: `frontend/package.json`, `frontend/vite.config.ts`, `frontend/tsconfig.json`, `frontend/index.html`, `frontend/login.html`, `frontend/admin.html`, `frontend/src/main.tsx`(임시), `frontend/src/login.tsx`(임시), `frontend/src/admin.tsx`(임시)

- [ ] **Step 1: pnpm 확보**

```bash
pnpm --version || corepack enable && corepack prepare pnpm@latest --activate
```

- [ ] **Step 2: package.json 작성** (버전 = 프로토타입 importmap과 동일 고정)

```json
{
  "name": "work-note-frontend",
  "private": true,
  "version": "0.1.0",
  "type": "module",
  "scripts": {
    "dev": "vite",
    "build": "tsc -b && vite build",
    "preview": "vite preview",
    "test": "vitest run",
    "test:watch": "vitest"
  },
  "dependencies": {
    "@codemirror/autocomplete": "6.18.6",
    "@codemirror/commands": "6.8.0",
    "@codemirror/lang-java": "6.0.1",
    "@codemirror/lang-javascript": "6.2.3",
    "@codemirror/lang-markdown": "6.3.2",
    "@codemirror/lang-sql": "6.8.0",
    "@codemirror/language": "6.10.8",
    "@codemirror/legacy-modes": "6.5.1",
    "@codemirror/state": "6.5.2",
    "@codemirror/view": "6.36.4",
    "@lezer/common": "1.2.3",
    "@lezer/highlight": "1.2.1",
    "@lezer/lr": "1.4.2",
    "@lezer/markdown": "1.3.2",
    "highlight.js": "11.9.0",
    "marked": "12.0.2",
    "mermaid": "10.9.1",
    "pretendard": "1.3.9",
    "react": "18.3.1",
    "react-dom": "18.3.1"
  },
  "devDependencies": {
    "@types/react": "^18.3.0",
    "@types/react-dom": "^18.3.0",
    "@vitejs/plugin-react": "^4.3.0",
    "typescript": "~5.7.0",
    "vite": "^6.0.0",
    "vitest": "^2.1.0"
  }
}
```

- [ ] **Step 3: vite.config.ts + tsconfig.json**

```ts
// frontend/vite.config.ts
import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";
import { resolve } from "node:path";

export default defineConfig({
  plugins: [react()],
  base: "./", // 폐쇄망 정적 서빙: 어느 경로에 놓여도 동작
  build: {
    rollupOptions: {
      input: {
        main: resolve(__dirname, "index.html"),
        login: resolve(__dirname, "login.html"),
        admin: resolve(__dirname, "admin.html"),
      },
    },
  },
  test: { environment: "node" },
});
```

```json
{
  "compilerOptions": {
    "target": "ES2022",
    "lib": ["ES2022", "DOM", "DOM.Iterable"],
    "module": "ESNext",
    "moduleResolution": "bundler",
    "jsx": "react-jsx",
    "strict": true,
    "noEmit": true,
    "skipLibCheck": true,
    "types": ["vite/client"]
  },
  "include": ["src"]
}
```

- [ ] **Step 4: 3개 HTML + 임시 entry 작성**

각 HTML은 프로토타입의 같은 이름 파일에서 **theme-FOUC 방지 인라인 스크립트와 `<title>`만 유지**하고, CDN `<script>`/importmap 전부 제거, body는 `<div id="root"></div>` + `<script type="module" src="/src/main.tsx"></script>` (login/admin은 각각 `login.tsx`/`admin.tsx`). 임시 entry는 `루트에 "WorkNote (migrating)" 텍스트 렌더`만.

```html
<!-- frontend/index.html -->
<!DOCTYPE html>
<html lang="ko">
<head>
  <meta charset="UTF-8" />
  <meta name="viewport" content="width=device-width, initial-scale=1.0" />
  <title>WorkNote</title>
  <script>
    try {
      var m = localStorage.getItem("wn.theme");
      document.documentElement.setAttribute("data-theme", m === "dark" ? "dark" : "light");
    } catch (e) {}
  </script>
</head>
<body>
  <div id="root"></div>
  <script type="module" src="/src/main.tsx"></script>
</body>
</html>
```

```tsx
// frontend/src/main.tsx (임시 — Task 11에서 교체)
import { createRoot } from "react-dom/client";
createRoot(document.getElementById("root")!).render(<div>WorkNote (migrating)</div>);
```

- [ ] **Step 5: 설치 + dev 서버 부팅 확인**

```bash
cd frontend && pnpm install && pnpm build
```
Expected: build 성공. `pnpm preview` 후 `curl http://localhost:4173/` 에 "WorkNote (migrating)" 포함.

- [ ] **Step 6: Commit** — `chore(frontend): scaffold vite+ts 3-entry MPA`

### Task 2: 도메인 타입 + id 유틸 (+vitest 가동 확인)

**Files:**
- Create: `frontend/src/types.ts`, `frontend/src/lib/id.ts`, `frontend/src/lib/id.test.ts`

- [ ] **Step 1: types.ts** — 스펙(§8.1 node 모델)과 프로토타입 SEED 형태를 그대로 타입화

```ts
// frontend/src/types.ts
export interface NoteNode {
  id: string;
  type: "note";
  title: string;
  tags: string[];
  updated: string; // YYYY-MM-DD
  content: string;
}
export interface FolderNode {
  id: string;
  type: "folder";
  name: string;
  open?: boolean;
  children: VaultNode[];
}
export type VaultNode = NoteNode | FolderNode;
export type VaultTree = VaultNode[];

export interface Settings {
  dark: boolean;
  sidebarWidth: number;
  density: "compact" | "comfortable" | "spacious";
  showIcons: boolean;
  guides: boolean;
  fontSize: number;
}
```

- [ ] **Step 2: 실패 테스트 → id.ts 구현 → 통과**

```ts
// frontend/src/lib/id.test.ts
import { describe, it, expect } from "vitest";
import { newId } from "./id";

describe("newId", () => {
  it("generates unique ids", () => {
    const ids = new Set(Array.from({ length: 1000 }, () => newId()));
    expect(ids.size).toBe(1000);
  });
  it("starts with u", () => {
    expect(newId()).toMatch(/^u/);
  });
});
```

구현은 프로토타입 `treeutil.js:3-4`의 `newId`를 그대로 export (`let counter` 모듈 스코프 유지).
Run: `pnpm test` Expected: PASS (2 tests)

- [ ] **Step 3: Commit** — `feat(frontend): domain types + id util with tests`

### Task 3: lib/tree.ts (TDD 포팅)

**Files:**
- Create: `frontend/src/lib/tree.test.ts`, `frontend/src/lib/tree.ts`
- Source: `docs/design-handoff/prototype/treeutil.js`

- [ ] **Step 1: 실패 테스트 작성** — 프로토타입 동작 명세 그대로

```ts
// frontend/src/lib/tree.test.ts
import { describe, it, expect } from "vitest";
import { walkTree, findNode, updateNode, insertChild, removeNode, flattenNotes, countNotes, dedupeIds } from "./tree";
import type { VaultTree, FolderNode, NoteNode } from "../types";

const note = (id: string, title: string): NoteNode => ({ id, type: "note", title, tags: [], updated: "2026-06-10", content: "" });
const folder = (id: string, name: string, children: VaultTree = []): FolderNode => ({ id, type: "folder", name, open: true, children });
const make = (): VaultTree => [folder("f1", "A", [note("n1", "one"), folder("f2", "B", [note("n2", "two")])]), note("n3", "root")];

describe("tree", () => {
  it("findNode returns node, parent and path", () => {
    const t = make();
    const { node, parentNode, path } = findNode(t, "n2");
    expect(node?.id).toBe("n2");
    expect(parentNode?.id).toBe("f2");
    expect(path).toEqual(["A", "B"]);
  });
  it("findNode misses → node null", () => {
    expect(findNode(make(), "zz").node).toBeNull();
  });
  it("updateNode returns new tree, original untouched", () => {
    const t = make();
    const t2 = updateNode(t, "n1", (n) => { (n as NoteNode).title = "ONE"; });
    expect((findNode(t2, "n1").node as NoteNode).title).toBe("ONE");
    expect((findNode(t, "n1").node as NoteNode).title).toBe("one");
  });
  it("insertChild into folder opens it and appends", () => {
    const t2 = insertChild(make(), "f2", note("n4", "four"));
    const f2 = findNode(t2, "f2").node as FolderNode;
    expect(f2.open).toBe(true);
    expect(f2.children.map((c) => c.id)).toContain("n4");
  });
  it("insertChild with null folderId appends to root", () => {
    const t2 = insertChild(make(), null, note("n4", "four"));
    expect(t2[t2.length - 1].id).toBe("n4");
  });
  it("removeNode removes nested node", () => {
    const t2 = removeNode(make(), "n2");
    expect(findNode(t2, "n2").node).toBeNull();
  });
  it("flattenNotes returns notes with folder paths", () => {
    const flat = flattenNotes(make());
    expect(flat.map((f) => f.note.id).sort()).toEqual(["n1", "n2", "n3"]);
    expect(flat.find((f) => f.note.id === "n2")?.path).toEqual(["A", "B"]);
  });
  it("countNotes counts recursively", () => {
    expect(countNotes(make()[0] as FolderNode)).toBe(2);
  });
  it("dedupeIds reassigns duplicates", () => {
    const t: VaultTree = [note("x", "a"), note("x", "b")];
    dedupeIds(t);
    expect(t[0].id).not.toBe(t[1].id);
  });
  it("walkTree visits every node with depth", () => {
    const seen: Array<[string, number]> = [];
    walkTree(make(), (n, _p, d) => seen.push([n.id, d]));
    expect(seen).toContainEqual(["n2", 2]);
  });
});
```

Run: `pnpm test` Expected: FAIL (`./tree` 없음)

- [ ] **Step 2: tree.ts 포팅** — `treeutil.js`의 `walk/findNode/updateNode/insertChild/removeNode/flattenNotes/countNotes/dedupeIds`를 공통 변환 규칙대로 이식. `newId`는 `./id`에서 import. 시그니처:

```ts
export function walkTree(tree: VaultTree, cb: (n: VaultNode, parent: FolderNode | null, depth: number, path: string[]) => void): void;
export function findNode(tree: VaultTree, id: string): { node: VaultNode | null; parentArr: VaultTree | null; parentNode: FolderNode | null; path: string[] };
export function updateNode(tree: VaultTree, id: string, mutate: (n: VaultNode) => void): VaultTree;
export function insertChild(tree: VaultTree, folderId: string | null, child: VaultNode): VaultTree;
export function removeNode(tree: VaultTree, id: string): VaultTree;
export function flattenNotes(tree: VaultTree): Array<{ note: NoteNode; path: string[] }>;
export function countNotes(folder: FolderNode): number;
export function dedupeIds(tree: VaultTree): VaultTree;
```

- [ ] **Step 3: `pnpm test`** Expected: PASS (전체)
- [ ] **Step 4: Commit** — `feat(frontend): port tree utils with full test coverage`

### Task 4: 상태 모듈 (reducer·persist·contextmenu) + 시드

**Files:**
- Create: `frontend/src/state/vaultReducer.ts`, `frontend/src/state/vaultReducer.test.ts`, `frontend/src/storage/local.ts`, `frontend/src/state/usePersist.ts`, `frontend/src/state/useContextMenu.ts`, `frontend/src/state/useVault.ts`, `frontend/src/seed.ts`
- Source: `prototype/hooks.jsx`, `prototype/data.js`

- [ ] **Step 1: reducer 실패 테스트**

```ts
// frontend/src/state/vaultReducer.test.ts
import { describe, it, expect } from "vitest";
import { vaultReducer, type VaultAction } from "./vaultReducer";
import type { VaultTree, FolderNode, NoteNode } from "../types";

const base = (): VaultTree => [
  { id: "f1", type: "folder", name: "A", open: true, children: [{ id: "n1", type: "note", title: "one", tags: [], updated: "2026-01-01", content: "x" }] },
];

describe("vaultReducer", () => {
  it("toggle flips folder open", () => {
    const t = vaultReducer(base(), { type: "toggle", id: "f1" });
    expect((t[0] as FolderNode).open).toBe(false);
  });
  it("rename sets folder name / note title", () => {
    let t = vaultReducer(base(), { type: "rename", id: "f1", value: "B" });
    expect((t[0] as FolderNode).name).toBe("B");
    t = vaultReducer(t, { type: "rename", id: "n1", value: "ONE" });
    expect(((t[0] as FolderNode).children[0] as NoteNode).title).toBe("ONE");
  });
  it("updateNote patches and stamps updated", () => {
    const t = vaultReducer(base(), { type: "updateNote", id: "n1", patch: { content: "y" } });
    const n = (t[0] as FolderNode).children[0] as NoteNode;
    expect(n.content).toBe("y");
    expect(n.updated).toMatch(/^\d{4}-\d{2}-\d{2}$/);
  });
  it("insert/remove round-trip", () => {
    let t = vaultReducer(base(), { type: "insert", folderId: "f1", node: { id: "n2", type: "note", title: "t", tags: [], updated: "", content: "" } });
    expect((t[0] as FolderNode).children).toHaveLength(2);
    t = vaultReducer(t, { type: "remove", id: "n2" });
    expect((t[0] as FolderNode).children).toHaveLength(1);
  });
  it("collapseAll closes every folder", () => {
    const t = vaultReducer(base(), { type: "collapseAll" });
    expect((t[0] as FolderNode).open).toBe(false);
  });
});
```

Run: `pnpm test` Expected: FAIL

- [ ] **Step 2: vaultReducer.ts 포팅** — `hooks.jsx:15-44`의 switch 그대로, tree util은 `../lib/tree` import. 액션 유니온 타입:

```ts
export type VaultAction =
  | { type: "toggle"; id: string } | { type: "open"; id: string } | { type: "collapseAll" }
  | { type: "insert"; folderId: string | null; node: VaultNode }
  | { type: "rename"; id: string; value: string } | { type: "remove"; id: string }
  | { type: "updateNote"; id: string; patch: Partial<NoteNode> }
  | { type: "replace"; tree: VaultTree };
```

- [ ] **Step 3: `pnpm test`** Expected: PASS

- [ ] **Step 4: 나머지 상태 모듈 포팅** (테스트는 순수 로직 아님 — 빌드로 검증)
  - `storage/local.ts`: `hooks.jsx`의 `load`/`save` → `export function load<T>(key: string, fallback: T): T` / `export function save(key: string, value: unknown): void`
  - `usePersist.ts`, `useContextMenu.ts`: `hooks.jsx` 해당 부분 그대로 (ContextMenu의 메뉴 항목 타입 `export interface MenuItem { icon?: string; label?: string; danger?: boolean; sep?: boolean; submenu?: MenuItem[]; onClick?: () => void }` 포함)
  - `useVault.ts`: `hooks.jsx:49-97` 그대로 — 디바운스 5초·beforeunload/visibility flush·`VKEY="wn.vault.v1"`·액션 객체 포함. `window.SEED` → `import { SEED } from "../seed"`
  - `seed.ts`: `data.js`의 SEED·SEED_DEFAULT_TITLE을 `VaultTree` 타입으로 export (내용 문자열 그대로 복사)

- [ ] **Step 5: `pnpm build`** Expected: 성공 (미사용 export 경고 무시)
- [ ] **Step 6: Commit** — `feat(frontend): port vault state (reducer/hooks/seed) with reducer tests`

### Task 5: lib/markdown.ts + 폰트 로컬화

**Files:**
- Create: `frontend/src/lib/markdown.ts`, `frontend/src/styles/fonts.css`, `frontend/src/assets/fonts/D2Coding.woff2`
- Source: `prototype/md.js`, `prototype/styles.css:7-11`

- [ ] **Step 1: D2Coding 폰트 다운로드(1회)**

```bash
mkdir -p frontend/src/assets/fonts
curl -sL -o frontend/src/assets/fonts/D2Coding.woff2 \
  "https://cdn.jsdelivr.net/gh/wan2land/d2coding@master/d2coding/D2Coding.woff2"
```
Expected: 파일 존재, `file` 출력에 "Web Open Font Format 2"

- [ ] **Step 2: fonts.css 작성** — CDN @import 대체

```css
/* frontend/src/styles/fonts.css */
@import "pretendard/dist/web/static/pretendard.css"; /* npm 패키지 — Vite가 번들 */
@font-face {
  font-family: "D2CodingWeb";
  src: url("../assets/fonts/D2Coding.woff2") format("woff2");
  font-weight: 400;
  font-display: swap;
}
```

- [ ] **Step 3: markdown.ts 포팅** — `md.js` 전체를 공통 규칙대로. 상단 import:

```ts
import { marked } from "marked";
import hljs from "highlight.js/lib/common";
import mermaid from "mermaid";
```

export 표면: `renderMarkdown(src: string): string` · `enhanceMermaid(root: Element): void` · `setMermaidTheme(isDark: boolean): void` · `mdToText(src: string): string`. 내부의 `window.marked`/`window.hljs`/`window.mermaid` 가드 분기는 import 보장으로 제거(나머지 로직 동일).

- [ ] **Step 4: `pnpm build`** Expected: 성공
- [ ] **Step 5: Commit** — `feat(frontend): port markdown pipeline, vendor fonts locally`

### Task 6: editor/cm.ts (CodeMirror 라이브 프리뷰)

**Files:**
- Create: `frontend/src/editor/cm.ts`
- Source: `prototype/cm.js` (419줄 — 이미 ESM)

- [ ] **Step 1: 포팅** — import 경로만 importmap URL → npm 패키지명으로 교체(이미 패키지명과 동일하므로 변경 없음). 파일 끝의 `window.WN_CM = {...}` 등록을 named export로 교체:

```ts
export { create, wrap, prefix, block, heading };
```

(함수 이름·시그니처는 cm.js의 WN_CM 객체 멤버 그대로. 내부에서 `window.renderMarkdown`/`window.enhanceMermaid`/`window.setMermaidTheme` 호출이 있으면 `../lib/markdown` import로 교체. `window.__wnView` 디버그 등록은 삭제.)

- [ ] **Step 2: `pnpm build`** Expected: 성공 (타입 에러는 CM 공식 타입 기준 수정 — 로직 변경 금지)
- [ ] **Step 3: Commit** — `feat(frontend): port CodeMirror live-preview module to npm imports`

### Task 7: 표현 컴포넌트 일괄 포팅

**Files:** (Create ← Source)
- `src/components/Icon.tsx` ← `prototype/icons.jsx`
- `src/components/ContextMenu.tsx` ← `prototype/ContextMenu.jsx`
- `src/components/Sidebar.tsx` ← `prototype/Sidebar.jsx`
- `src/components/Outline.tsx` ← `prototype/Outline.jsx`
- `src/components/SearchModal.tsx` ← `prototype/SearchModal.jsx`
- `src/components/ProfileModal.tsx` ← `prototype/Profile.jsx`

- [ ] **Step 1: 6개 파일 포팅** — 공통 변환 규칙 적용. 파일별 import 매핑:

| 파일 | window.* 소비 → import |
|---|---|
| Icon | (없음) — `export function Icon({ name }: { name: string })` |
| ContextMenu | `Icon` → `./Icon` |
| Sidebar | `Icon` → `./Icon`, `countNotes` → `../lib/tree` |
| Outline | (viewRef prop만) — `window.performance`는 표준 API, 유지 |
| SearchModal | `Icon` → `./Icon`, `mdToText` → `../lib/markdown` |
| ProfileModal | `Icon` → `./Icon` |

props 타입은 호출부(App)에서 넘기는 형태 그대로 interface로 선언. 콜백은 `(...) => void`.

- [ ] **Step 2: `pnpm build`** Expected: 성공
- [ ] **Step 3: Commit** — `feat(frontend): port presentational components`

### Task 8: Editor.tsx

**Files:**
- Create: `frontend/src/components/Editor.tsx`
- Source: `prototype/Editor.jsx`

- [ ] **Step 1: 포팅 + 단순화** — `* as cm from "../editor/cm"` import. 번들에선 CM이 항상 존재하므로:
  - `wn-cm-ready` 이벤트 대기·4500ms 타이머·`fallback` textarea 경로 **삭제** (CDN 실패 대비책이었음 — 번들에선 죽은 코드)
  - mount effect는 `cm.create(hostRef.current, { doc, onChange })` 직행
  - 툴바 등록의 `act(cmFn, fbFn)` → cmFn만 직접 호출로 축약, TEMPLATES·태그 로직·title autogrow는 그대로
- [ ] **Step 2: `pnpm build`** Expected: 성공
- [ ] **Step 3: Commit** — `feat(frontend): port Editor, drop CDN-failure fallback path`

### Task 9: commands/exportCommands.ts

**Files:**
- Create: `frontend/src/commands/exportCommands.ts`
- Source: `prototype/export.js`

- [ ] **Step 1: 포팅** — 커맨드 타입 부여:

```ts
import type { NoteNode } from "../types";
export interface ExportCtx { openNote?: (n: NoteNode) => void; toast?: (msg: string, icon?: string) => void; }
export interface ExportCommand { id: string; label: string; icon: string; run: (note: NoteNode, ctx: ExportCtx) => void | Promise<void>; }
export const exportCommands: ExportCommand[];
export function buildMarkdown(note: NoteNode): string;
```

- [ ] **Step 2: `pnpm build`** → Commit — `feat(frontend): port export commands`

### Task 10: useSettings + SettingsModal (tweaks-panel 대체)

**Files:**
- Create: `frontend/src/state/useSettings.ts`, `frontend/src/components/SettingsModal.tsx`

tweaks-panel.jsx(541줄, 디자인 툴 edit-mode 잔재)는 포팅하지 않는다. 동일 6개 설정을 제품 UI로 제공: 사이드바 푸터 톱니(기존 "준비 중" 토스트 자리)에서 열리는 모달.

- [ ] **Step 1: useSettings.ts**

```ts
// frontend/src/state/useSettings.ts
import { usePersist } from "./usePersist";
import type { Settings } from "../types";

export const SETTINGS_DEFAULTS: Settings = {
  dark: false, sidebarWidth: 264, density: "comfortable",
  showIcons: true, guides: true, fontSize: 16,
};

export function useSettings() {
  const [settings, setSettings] = usePersist<Settings>("wn.settings.v1", SETTINGS_DEFAULTS);
  const set = <K extends keyof Settings>(k: K, v: Settings[K]) =>
    setSettings((s: Settings) => ({ ...s, [k]: v }));
  return { settings: { ...SETTINGS_DEFAULTS, ...settings }, set };
}
```

(usePersist의 setter가 함수형 업데이트를 지원하도록 Task 4 포팅 시 `useState` setter 그대로 노출했는지 확인 — 프로토타입과 동일.)

- [ ] **Step 2: SettingsModal.tsx** — 기존 모달 스타일(.modal-ov/.modal — admin.css가 아닌 styles.css에 검색 모달 류 스타일 존재; SearchModal과 같은 오버레이 패턴 사용)로 구현. 컨트롤 6개:

```tsx
// frontend/src/components/SettingsModal.tsx — 구조 (createElement 스타일, 클래스는 SearchModal 오버레이 재사용)
import { Icon } from "./Icon";
import type { Settings } from "../types";

interface Props { settings: Settings; onSet: <K extends keyof Settings>(k: K, v: Settings[K]) => void; onClose: () => void; }

export function SettingsModal({ settings, onSet, onClose }: Props) {
  // 오버레이: onMouseDown=onClose, 내부 stopPropagation (SearchModal과 동일 패턴)
  // 행 구성 (라벨 — 컨트롤):
  //  "다크 모드"      <input type="checkbox" checked={settings.dark}      onChange=...>
  //  "사이드바 너비"  <input type="range" min=220 max=360 step=4 value={settings.sidebarWidth}> + px 표시
  //  "밀도"          3버튼 세그먼트 compact/comfortable/spacious (active 클래스)
  //  "파일 아이콘"    checkbox showIcons
  //  "계층 안내선"    checkbox guides
  //  "본문 글자 크기" <input type="range" min=14 max=20 step=1 value={settings.fontSize}> + px 표시
  // 하단: 닫기 버튼 (.btn)
}
```

스타일 부족분은 `frontend/src/styles/settings.css`에 최소 추가(기존 변수 `--bg-elevated`·`--border` 등만 사용, 새 색 금지).

- [ ] **Step 3: `pnpm build`** → Commit — `feat(frontend): settings modal replaces design-tool tweaks panel`

### Task 11: App.tsx + main.tsx (메인 페이지 완성)

**Files:**
- Create: `frontend/src/App.tsx`, `frontend/src/styles/app.css`(= prototype/styles.css에서 CDN @import 2줄 제거본)
- Modify: `frontend/src/main.tsx`
- Source: `prototype/App.jsx`

- [ ] **Step 1: styles 이식** — `prototype/styles.css` → `src/styles/app.css` 복사, 7행 Pretendard @import와 9-11행 D2Coding @font-face 삭제(→ fonts.css가 대체). 그 외 변경 금지.

- [ ] **Step 2: App.tsx 포팅** — `App.jsx` 전체를 공통 규칙대로. 변경점만:
  - `useTweaks(TWEAK_DEFAULTS)` → `useSettings()` (변수 `t` → `settings`, `setTweak(k,v)` → `set(k,v)`) — TWEAK_DEFAULTS 블록 삭제
  - 말미 TweaksPanel JSX 블록 전체 삭제 → `settingsOpen` state + `<SettingsModal>` 렌더로 대체
  - Sidebar `onSettings`: 토스트 → `() => setSettingsOpen(true)`
  - import: Sidebar/Editor/SearchModal/ContextMenu/ProfileModal/Outline/Icon/SettingsModal, useVault/usePersist/useContextMenu/useSettings, findNode/flattenNotes, newId, exportCommands, setMermaidTheme, seed의 SEED_DEFAULT_TITLE
  - 그 외 로직(TB_GROUPS, 단축키, 토스트, 브레드크럼, density 매핑, CSS 변수) 그대로

- [ ] **Step 3: main.tsx 완성**

```tsx
// frontend/src/main.tsx
import { createRoot } from "react-dom/client";
import "./styles/fonts.css";
import "./styles/app.css";
import "./styles/settings.css";
import { App } from "./App";

createRoot(document.getElementById("root")!).render(<App />);
```

- [ ] **Step 4: 검증 — 프로토타입과 나란히 비교**

```bash
cd frontend && pnpm dev &
```
브라우저(또는 browse 도구)로 `http://localhost:5173` vs `http://127.0.0.1:8001/index.html`:
사이드바 트리·노트 열기·라이브 프리뷰·mermaid 렌더·⌘K 검색·우클릭 메뉴·다크 토글·내보내기 3종·설정 모달 동작 확인.

- [ ] **Step 5: Commit** — `feat(frontend): main page complete on vite`

### Task 12: 로그인 페이지

**Files:**
- Create: `frontend/src/login/LoginPage.tsx`, `frontend/src/styles/login.css`(복사), `frontend/src/login.tsx`
- Source: `prototype/login.jsx`, `prototype/login.css`

- [ ] **Step 1: 포팅** — login.jsx → LoginPage.tsx (Icon import). login.css는 그대로 복사. entry:

```tsx
// frontend/src/login.tsx
import { createRoot } from "react-dom/client";
import "./styles/fonts.css";
import "./styles/app.css";
import "./styles/login.css";
import { LoginPage } from "./login/LoginPage";
createRoot(document.getElementById("root")!).render(<LoginPage />);
```

주의: 프로토타입 내 페이지 이동(`location.href = "index.html"` 등) 경로 유지.

- [ ] **Step 2: 검증** `http://localhost:5173/login.html` vs 프로토타입 → Commit — `feat(frontend): login page`

### Task 13: 관리자 페이지

**Files:** (Create ← Source)
- `src/admin/data.ts` ← `prototype/admin-data.js` (ADMIN_* 객체들을 typed export로; `AdminTreeNode`·`Grant` 타입 선언 포함)
- `src/admin/common.tsx` ← `prototype/admin-common.jsx` (StatusBadge·RoleBadge·Modal·Empty·SkeletonTable·Switch·Avatar·SecHead·useToast export)
- `src/admin/screens/Dashboard.tsx`·`Pending.tsx`·`Users.tsx` ← `prototype/admin-screens-1.jsx` (3분할, `walkAdminTree`는 `src/admin/tree.ts`로)
- `src/admin/screens/Permissions.tsx`·`Roles.tsx`·`Audit.tsx`·`Security.tsx` ← `prototype/admin-screens-2.jsx` (4분할)
- `src/admin/AdminApp.tsx` ← `prototype/AdminApp.jsx`
- `src/styles/admin.css` ← `prototype/admin.css` (복사)
- Modify: `frontend/src/admin.tsx` (entry — fonts/app/admin css import + AdminApp 마운트)

- [ ] **Step 1: data.ts → common.tsx → screens 7개 → AdminApp 순서로 포팅** (의존 방향순. 화면 간 참조는 전부 명시 import)
- [ ] **Step 2: 검증** `http://localhost:5173/admin.html` vs 프로토타입 — 7개 섹션 전환·권한 트리 토글·역할 편집 모달·감사 CSV/리포트 다운로드 확인
- [ ] **Step 3: Commit** — `feat(frontend): admin console pages`

### Task 14: 빌드 검수 (Phase 1 완료 게이트)

- [ ] **Step 1: 프로덕션 빌드 + 외부 의존 0 확인**

```bash
cd frontend && pnpm build
grep -rEo "https?://[a-z0-9.-]+" dist/ --include='*.html' --include='*.js' --include='*.css' -h | sort -u
```
Expected: CDN 도메인(unpkg/jsdelivr/cdnjs/esm.sh) **0건** (svg xmlns 네임스페이스 URL 등 비요청성 문자열만 허용)

- [ ] **Step 2: preview로 3페이지 스모크**

```bash
pnpm preview &   # :4173
for p in "" login.html admin.html; do curl -s -o /dev/null -w "%{http_code} /$p\n" "http://localhost:4173/$p"; done
```
Expected: 모두 200. 브라우저로 3페이지 동작 재확인.

- [ ] **Step 3: Commit** — `chore(frontend): phase 1 complete — self-contained dist, zero CDN`

---

## Phase 2 — 구조 개선

### Task 15: 트리 연산 구조 공유 (전체 clone 제거)

**Files:**
- Modify: `frontend/src/lib/tree.ts`, `frontend/src/lib/tree.test.ts`

- [ ] **Step 1: 참조 보존 실패 테스트 추가**

```ts
// tree.test.ts에 추가
describe("structural sharing", () => {
  it("updateNode preserves untouched sibling references", () => {
    const t = make();
    const t2 = updateNode(t, "n1", (n) => { (n as NoteNode).title = "ONE"; });
    expect(t2[1]).toBe(t[1]);                                  // 건드리지 않은 루트 노트: 같은 참조
    const f2old = (t[0] as FolderNode).children[1];
    const f2new = (t2[0] as FolderNode).children[1];
    expect(f2new).toBe(f2old);                                  // 형제 폴더 서브트리: 같은 참조
    expect(t2[0]).not.toBe(t[0]);                               // 변경 경로: 새 객체
  });
  it("insertChild preserves sibling references", () => {
    const t = make();
    const t2 = insertChild(t, "f2", note("n9", "nine"));
    expect(t2[1]).toBe(t[1]);
  });
  it("removeNode preserves sibling references", () => {
    const t = make();
    const t2 = removeNode(t, "n2");
    expect(t2[1]).toBe(t[1]);
  });
});
```

Run: `pnpm test` Expected: 새 테스트 3개 FAIL (deep clone이라 참조 모두 교체됨)

- [ ] **Step 2: 경로 복사 구현** — `updateNode`/`insertChild`/`removeNode`를 재귀 경로-복사로 재작성:

```ts
// 핵심 패턴 (updateNode): id를 포함한 경로의 노드만 새로 만들고 나머지는 참조 재사용
function updateAt(nodes: VaultTree, id: string, mutate: (n: VaultNode) => void): VaultTree | null {
  let changed = false;
  const next = nodes.map((n) => {
    if (n.id === id) {
      const copy = n.type === "folder" ? { ...n, children: [...n.children] } : { ...n };
      mutate(copy);
      changed = true;
      return copy;
    }
    if (n.type === "folder") {
      const sub = updateAt(n.children, id, mutate);
      if (sub) { changed = true; return { ...n, children: sub }; }
    }
    return n;
  });
  return changed ? next : null;
}
export function updateNode(tree: VaultTree, id: string, mutate: (n: VaultNode) => void): VaultTree {
  return updateAt(tree, id, mutate) ?? tree;
}
```

`insertChild`/`removeNode`도 같은 패턴(대상 폴더/배열까지의 경로만 복사). `vaultReducer`의 `collapseAll`은 전 노드 변경이므로 기존 방식 유지 가능.

- [ ] **Step 3: `pnpm test`** Expected: 전체 PASS (기존 불변성 테스트 + 신규 참조 테스트)
- [ ] **Step 4: Commit** — `perf(frontend): structural sharing for tree ops (no full-tree clone per keystroke)`

### Task 16: VaultRepository (storage 어댑터)

**Files:**
- Create: `frontend/src/storage/VaultRepository.ts`, `frontend/src/storage/LocalStorageRepository.ts`, `frontend/src/storage/LocalStorageRepository.test.ts`
- Modify: `frontend/src/state/useVault.ts`

- [ ] **Step 1: 인터페이스 + 구현 + 테스트**

```ts
// frontend/src/storage/VaultRepository.ts
import type { VaultTree } from "../types";
/** 추후 SQLite(1단계)·HTTP API(2단계) 구현체로 교체되는 지점. async 고정. */
export interface VaultRepository {
  load(): Promise<VaultTree | null>;   // null = 저장본 없음(시드 사용)
  save(tree: VaultTree): Promise<void>;
}
```

```ts
// frontend/src/storage/LocalStorageRepository.ts
import type { VaultRepository } from "./VaultRepository";
import type { VaultTree } from "../types";

export class LocalStorageRepository implements VaultRepository {
  constructor(private key = "wn.vault.v1") {}
  async load(): Promise<VaultTree | null> {
    try { const v = localStorage.getItem(this.key); return v ? (JSON.parse(v) as VaultTree) : null; }
    catch { return null; }
  }
  async save(tree: VaultTree): Promise<void> {
    try { localStorage.setItem(this.key, JSON.stringify(tree)); } catch { /* quota — 무시(프로토타입 동작 유지) */ }
  }
  /** beforeunload용 동기 플러시 — async가 보장되지 않는 마지막 순간 */
  saveSync(tree: VaultTree): void {
    try { localStorage.setItem(this.key, JSON.stringify(tree)); } catch { /* ignore */ }
  }
}
```

테스트(vitest, 환경 node — localStorage 모킹):

```ts
// frontend/src/storage/LocalStorageRepository.test.ts
import { describe, it, expect, beforeEach, vi } from "vitest";
import { LocalStorageRepository } from "./LocalStorageRepository";
import type { VaultTree } from "../types";

const mem = new Map<string, string>();
beforeEach(() => {
  mem.clear();
  vi.stubGlobal("localStorage", {
    getItem: (k: string) => mem.get(k) ?? null,
    setItem: (k: string, v: string) => { mem.set(k, v); },
  });
});

describe("LocalStorageRepository", () => {
  const tree: VaultTree = [{ id: "n1", type: "note", title: "t", tags: [], updated: "", content: "" }];
  it("round-trips a tree", async () => {
    const repo = new LocalStorageRepository("k");
    await repo.save(tree);
    expect(await repo.load()).toEqual(tree);
  });
  it("returns null when empty", async () => {
    expect(await new LocalStorageRepository("none").load()).toBeNull();
  });
});
```

Run: `pnpm test` Expected: PASS

- [ ] **Step 2: useVault를 repo 경유로 수정** — 직접 `load/save` 호출 제거:

```ts
// useVault.ts 변경 골자
export function useVault(repo: VaultRepository = new LocalStorageRepository()) {
  const [tree, dispatch] = useReducer(vaultReducer, null as unknown as VaultTree, () => dedupeIds(SEED));
  const [ready, setReady] = useState(false);
  useEffect(() => {                      // 비동기 초기 로드 (localStorage는 즉시 resolve)
    repo.load().then((saved) => {
      if (saved) dispatch({ type: "replace", tree: dedupeIds(saved) });
      setReady(true);
    });
  }, []);
  // 디바운스 저장: save(VKEY, ...) → repo.save(treeRef.current) (ready 전에는 저장 금지)
  // beforeunload/visibility flush: repo가 LocalStorageRepository면 saveSync 사용
  return { tree, actions, savedTick, ready };
}
```

App은 `ready` 전에 빈 화면 대신 기존 레이아웃 골격만 렌더(로딩 플래시 방지 — localStorage는 동기적이라 실질 0ms).

- [ ] **Step 3: `pnpm test && pnpm build`** + 브라우저에서 새로고침 시 데이터 유지 확인 (작성→새로고침→내용 보존)
- [ ] **Step 4: Commit** — `refactor(frontend): vault persistence behind VaultRepository (SQLite/API swap point)`

### Task 17: 마무리 — 문서·최종 QA

**Files:**
- Create: `frontend/README.md`
- Modify: `frontend/src/` 내 잔여 정리, 루트 `CLAUDE.md`(프로젝트용 신규)

- [ ] **Step 1: 죽은 코드 스캔** — `prototype` 참조·미사용 export·`window.` 잔재 grep:

```bash
cd frontend/src && grep -rn "window\." --include='*.ts*' | grep -vE "window\.(addEventListener|removeEventListener|print|location|getSelection|matchMedia|innerWidth|innerHeight|setTimeout|open)"
```
Expected: 0건 (전역 등록·소비 완전 제거 확인)

- [ ] **Step 2: frontend/README.md** — 스택, `pnpm dev/build/test`, 폐쇄망 배포 방법(dist를 정적 서버에 복사), 디렉토리 구조, 프로토타입 위치(docs/design-handoff/prototype) 기록

- [ ] **Step 3: 루트 CLAUDE.md 작성** — 프로젝트 구조(frontend/backend/docs), 스펙·플랜 위치, 프런트 아키텍처 원칙(기존 prototype/CLAUDE.md 원칙 계승) 요약

- [ ] **Step 4: 최종 QA** — `pnpm test && pnpm build && pnpm preview` + 3페이지 전 기능 체크리스트(Task 11/12/13의 검증 항목 재실행)

- [ ] **Step 5: Commit** — `docs: frontend README + project CLAUDE.md, phase 2 complete`

---

## Self-Review 노트

- **스펙 커버리지:** 이 플랜은 "프런트 시안 이주·개선" 범위만. 권한 스펙(deny/공유링크 등)의 실제 동작은 백엔드 계획에서 — admin 화면은 시드 데이터 기반 UI 그대로 이식이 맞음(스펙 §0: 1단계는 권한 비활성).
- **타입 일관성:** `VaultNode`/`NoteNode`/`FolderNode`/`Settings`는 Task 2 정의를 전 태스크가 공유. reducer 액션 유니온은 Task 4, 커맨드 타입은 Task 9에서 단일 정의.
- **알려진 동작 변경(의도됨) 2건:** ① Editor의 CDN 실패 폴백 textarea 삭제(Task 8) ② tweaks-panel → SettingsModal(Task 10, 설정이 localStorage에 영속화되는 것은 개선). 그 외 픽셀·동작 동일이 게이트.
