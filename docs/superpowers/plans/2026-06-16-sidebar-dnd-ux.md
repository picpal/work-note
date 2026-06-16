# 사이드바 DnD UX 개선 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 사이드바 트리에서 노트·폴더를 폴더 안에만 드롭 가능하게 하고, 드래그 중 목적지 폴더만 하이라이트하며, 최상위(depth-0) 폴더는 이동 불가로 고정하고, 루트 생성을 관리자/local로 제한한다.

**Architecture:** 프런트엔드 전용. 드롭 판정 순수 로직(`canDropOn`)을 TDD로 강화하고, 컴포넌트(Sidebar/App/MoveModal) 배선은 빌드+기존 테스트 스위트+QA로 검증한다(레포 관례: 컴포넌트 렌더 테스트 없음, 순수 로직만 단위 테스트).

**Tech Stack:** Vite 6 + TypeScript + React 18, Vitest(node env), pnpm.

**병렬 실행 설계:** 아래 3개 Task는 **파일 교집합이 0**이라 동시 실행·병합이 안전하다.
- Task 1 → `frontend/src/lib/dnd.ts`, `frontend/src/lib/dnd.test.ts`
- Task 2 → `frontend/src/components/MoveModal.tsx`
- Task 3 → `frontend/src/components/Sidebar.tsx`, `frontend/src/App.tsx`, `frontend/src/styles/app.css`

세 Task는 서로의 export에 의존하지 않는다(`canDropOn` 시그니처 불변, Sidebar는 `depth` 인라인, App은 `findNode` 인라인). 통합 검증은 세 Task 병합 후 오케스트레이터가 `pnpm test` + `pnpm build` + QA로 수행한다. 모든 명령은 `frontend/`에서 실행.

**스펙:** `docs/superpowers/specs/2026-06-16-sidebar-dnd-ux-design.md`

---

### Task 1: `canDropOn` — 루트 드롭 제거 + 최상위 폴더 immovable (TDD)

**Files:**
- Modify: `frontend/src/lib/dnd.ts`
- Test: `frontend/src/lib/dnd.test.ts`

- [ ] **Step 1: 실패하는 테스트로 교체**

`frontend/src/lib/dnd.test.ts` 전체를 아래로 교체 (픽스처에 `f4` 중첩 폴더 추가 — 이동 가능한 폴더의 자손-폴더 가드를 테스트하기 위함):

```ts
import { describe, it, expect } from "vitest";
import { canDropOn } from "./dnd";
import type { VaultTree } from "../types";

const tree: VaultTree = [
  { id: "f1", type: "folder", name: "F1", children: [
    { id: "n1", type: "note", title: "N1", tags: [], updated: "2026-06-13", content: "" },
    { id: "f2", type: "folder", name: "F2", children: [
      { id: "n2", type: "note", title: "N2", tags: [], updated: "2026-06-13", content: "" },
      { id: "f4", type: "folder", name: "F4", children: [] },
    ] },
  ] },
  { id: "f3", type: "folder", name: "F3", children: [] },
  { id: "n3", type: "note", title: "N3", tags: [], updated: "2026-06-13", content: "" },
];

describe("canDropOn", () => {
  it("최상위 노트를 폴더로 드롭 허용", () => { expect(canDropOn(tree, "n3", "f1")).toBe(true); });
  it("중첩 노트를 다른 폴더로 드롭 허용", () => { expect(canDropOn(tree, "n2", "f3")).toBe(true); });
  it("중첩 폴더를 다른 폴더로 드롭 허용", () => { expect(canDropOn(tree, "f2", "f3")).toBe(true); });
  it("최상위 폴더는 이동 불가(immovable)", () => { expect(canDropOn(tree, "f3", "f1")).toBe(false); });
  it("최상위 폴더는 어느 폴더로도 불가", () => { expect(canDropOn(tree, "f1", "f3")).toBe(false); });
  it("노트 위로는 드롭 불가", () => { expect(canDropOn(tree, "n3", "n1")).toBe(false); });
  it("자기 자신 위로 불가", () => { expect(canDropOn(tree, "f2", "f2")).toBe(false); });
  it("자손 폴더로 불가(이동 가능 소스)", () => { expect(canDropOn(tree, "f2", "f4")).toBe(false); });
  it("이미 그 부모면 무변경(불가)", () => { expect(canDropOn(tree, "n1", "f1")).toBe(false); });
  it("루트로는 드롭 불가(중첩 노트)", () => { expect(canDropOn(tree, "n1", null)).toBe(false); });
  it("루트로는 드롭 불가(최상위 노트)", () => { expect(canDropOn(tree, "n3", null)).toBe(false); });
  it("존재하지 않는 dragged 불가", () => { expect(canDropOn(tree, "zzz", "f1")).toBe(false); });
});
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `pnpm vitest run src/lib/dnd.test.ts`
Expected: FAIL — 현재 `canDropOn`은 루트 드롭(`null`)을 허용하고 최상위 폴더 이동을 막지 않으므로 `루트로는 드롭 불가*`, `최상위 폴더는*` 케이스가 실패.

- [ ] **Step 3: `canDropOn` 구현 교체**

`frontend/src/lib/dnd.ts` 전체를 아래로 교체:

```ts
import { findNode, isSelfOrDescendant } from "./tree";
import type { VaultTree } from "../types";

/** DnD 드롭 허용 판정. 루트 드롭은 불가(폴더 안에만 이동). 최상위(depth-0) 폴더는 이동 불가.
 *  허용 조건: dragged 존재 · 최상위 폴더 아님 · 타깃이 폴더(루트 null 불가) ·
 *  자기/자손 폴더 아님 · 현재 부모와 다름. */
export function canDropOn(tree: VaultTree, draggedId: string, targetId: string | null): boolean {
  if (targetId === null) return false;                 // 루트 드롭 제거 — 폴더 안에만
  if (draggedId === targetId) return false;
  const dragged = findNode(tree, draggedId);
  if (!dragged.node) return false;
  if (dragged.node.type === "folder" && dragged.parentNode === null) return false; // 최상위 폴더 immovable
  const target = findNode(tree, targetId);
  if (!target.node || target.node.type !== "folder") return false;
  if (isSelfOrDescendant(tree, draggedId, targetId)) return false;
  const currentParentId = dragged.parentNode?.id ?? null;
  if (currentParentId === targetId) return false;
  return true;
}
```

- [ ] **Step 4: 테스트 통과 확인**

Run: `pnpm vitest run src/lib/dnd.test.ts`
Expected: PASS (12 passed).

- [ ] **Step 5: 커밋**

```bash
git add frontend/src/lib/dnd.ts frontend/src/lib/dnd.test.ts
git commit -m "feat(dnd): 루트 드롭 제거 + 최상위 폴더 immovable (canDropOn)"
```

---

### Task 2: MoveModal — "루트(최상위)" 옵션 제거

**Files:**
- Modify: `frontend/src/components/MoveModal.tsx`

루트로의 이동을 제거한다(스펙 §4.6). 컴포넌트 렌더 테스트는 레포에 없으므로 빌드+수동 확인으로 검증.

- [ ] **Step 1: 옵션 목록 렌더 교체**

`frontend/src/components/MoveModal.tsx`에서 `h("div", { className: "mv-list" }, ...)` 블록을 찾는다. 현재는 첫 자식으로 "루트 (최상위)" `h("button", ...)`가 있고 그 뒤 `options.map(...)`가 있다. 이 `mv-list` 블록 전체를 아래로 교체(루트 버튼 삭제 + 빈 상태 처리):

```js
          h("div", { className: "mv-list" },
            options.length === 0
              ? h("div", { style: { padding: "14px", color: "var(--text-3)", fontSize: 13 } }, "이동 가능한 폴더가 없습니다")
              : options.map((o) =>
                  h("button", {
                    key: o.id,
                    className: "mv-opt" + (selected && target === o.id ? " sel" : ""),
                    disabled: o.id === currentParentId,
                    onClick: () => pick(o.id),
                  },
                    h("span", { className: "ic" }, h(Icon, { name: o.isRoot ? "space" : "folder" })),
                    h("span", { className: "lbl" }, o.label),
                    o.id === currentParentId ? h("span", { className: "here" }, "현재 위치") : null))),
```

`pick`, `target`(string | null), `selected`, "이동" 버튼(`disabled: !selected || busy`)은 그대로 둔다 — 이제 `target`은 폴더 id로만 설정되고 빈 상태에선 선택 버튼이 없어 진행 불가(의도된 동작).

- [ ] **Step 2: 타입체크/빌드 확인**

Run: `pnpm build`
Expected: tsc 에러 없이 dist 생성. (참고: `pick`/`target`의 `null` 분기는 남아 있어도 타입 에러 없음 — 호출되지 않을 뿐.)

- [ ] **Step 3: 커밋**

```bash
git add frontend/src/components/MoveModal.tsx
git commit -m "feat(move): MoveModal 루트(최상위) 옵션 제거 + 빈 상태 안내"
```

---

### Task 3: Sidebar/App 배선 — 최상위 폴더 드래그 잠금 · 단일 폴더 하이라이트 · 툴바 생성 제거 · 루트 생성 게이팅

**Files:**
- Modify: `frontend/src/components/Sidebar.tsx`
- Modify: `frontend/src/App.tsx`
- Modify: `frontend/src/styles/app.css`

컴포넌트 배선이라 단위 테스트 대신 빌드+기존 스위트+QA로 검증.

- [ ] **Step 1: Sidebar Row — 최상위 폴더 드래그 잠금**

`frontend/src/components/Sidebar.tsx`의 `Row` 안 `rowEl` 생성에서 `draggable: !renaming,` 한 줄을 아래로 교체:

```js
      draggable: !renaming && !(isFolder && depth === 0),
```

- [ ] **Step 2: Sidebar `.tree` — 루트 드롭 핸들러/클래스 제거**

같은 파일에서 `.tree` 컨테이너 `React.createElement("div", {...}, ...)`를 찾는다. 현재 props:
```js
        className: "tree" + (props.dragOverId === "__ROOT__" ? " root-drop" : ""),
        onContextMenu: (e) => { ...; props.onContext(e.clientX, e.clientY, null); },
        onDragOver: (e) => props.onNodeDragOver(null, e),
        onDragLeave: () => props.onNodeDragLeave(null),
        onDrop: (e) => props.onNodeDrop(null, e),
```
이 props 객체를 아래로 교체(`onContextMenu`만 유지, root-drop·드래그 핸들러 제거):

```js
        className: "tree",
        onContextMenu: (e: React.MouseEvent) => { e.preventDefault(); props.onContext(e.clientX, e.clientY, null); },
```

- [ ] **Step 3: Sidebar 툴바 — 생성 버튼 제거**

같은 파일 `sb-toolbar` 블록에서 "새 노트"·"새 폴더" 두 버튼을 제거하고 "모두 접기"만 남긴다. 아래 블록을:
```js
    React.createElement(
      "div", { className: "sb-toolbar" },
      React.createElement("button", { className: "icon-btn", title: "새 노트", onClick: () => onNewNote(null) },
        React.createElement(Icon, { name: "newNote" })),
      React.createElement("button", { className: "icon-btn", title: "새 폴더", onClick: () => onNewFolder(null) },
        React.createElement(Icon, { name: "folderPlus" })),
      React.createElement("div", { className: "spacer" }),
      React.createElement("button", { className: "icon-btn", title: "모두 접기", onClick: onCollapseAll },
        React.createElement(Icon, { name: "collapseAll" }))
    ),
```
아래로 교체:
```js
    React.createElement(
      "div", { className: "sb-toolbar" },
      React.createElement("div", { className: "spacer" }),
      React.createElement("button", { className: "icon-btn", title: "모두 접기", onClick: onCollapseAll },
        React.createElement(Icon, { name: "collapseAll" }))
    ),
```

- [ ] **Step 4: Sidebar — 미사용 props 정리**

같은 파일에서:
1. `interface SidebarProps`의 `onNewNote: (folderId: string | null) => void;` 와 `onNewFolder: (folderId: string | null) => void;` 두 줄 제거.
2. `export function Sidebar`의 구조분해 `const { tree, brand, onOpenSearch, onNewNote, onNewFolder, onCollapseAll, onToggleSidebar } = props;` 에서 `onNewNote, onNewFolder,` 제거 → `const { tree, brand, onOpenSearch, onCollapseAll, onToggleSidebar } = props;`

(`RowProps`에는 onNewNote/onNewFolder가 없으므로 Row는 수정 불필요.)

- [ ] **Step 5: App — Sidebar에 넘기던 생성 props 제거**

`frontend/src/App.tsx`에서 `createElement(Sidebar, { ... })` 호출의 `onNewNote: newNoteIn, onNewFolder: newFolderIn,` 줄을 제거한다. (`newNoteIn`/`newFolderIn`은 onContext에서 계속 사용하므로 함수 자체는 남긴다.)

- [ ] **Step 6: App — 루트 생성 게이팅 + 최상위 폴더 "이동" 숨김 (onContext)**

`frontend/src/App.tsx`의 `onContext` 함수를 아래로 교체(`canCreateAtRoot`는 기존 `showAdmin` 기준과 동일):

```js
  const onContext = (x: number, y: number, node: any) => {
    const canCreateAtRoot = storageMode === "local" || isAdmin;
    let items;
    if (!node) {
      if (!canCreateAtRoot) return;   // 일반 사용자: 빈 영역(루트) 우클릭 무반응
      items = [
        { icon: "newNote", label: "새 노트", onClick: () => newNoteIn(null) },
        { icon: "folderPlus", label: "새 폴더", onClick: () => newFolderIn(null) },
      ];
    } else if (node.type === "folder") {
      const isTopFolder = findNode(tree, node.id).parentNode === null;
      items = [
        { icon: "newNote", label: "새 노트", onClick: () => newNoteIn(node.id) },
        { icon: "folderPlus", label: "새 폴더", onClick: () => newFolderIn(node.id) },
        { sep: true },
        ...(isTopFolder ? [] : [{ icon: "move", label: "이동", onClick: () => setMoveTarget({ id: node.id, name: node.name }) }]),
        { icon: "edit", label: "이름 변경", onClick: () => setRenamingId(node.id) },
        { icon: "trash", label: "삭제", danger: true, onClick: () => removeNode(node.id) },
      ];
    } else {
      items = [
        { icon: "export", label: "내보내기", submenu: exportSub(node) },
        ...(storageMode === "http"
          ? [{ icon: "link", label: "공유 링크", onClick: () => setShareNote({ id: node.id, name: node.title || "제목 없음" }) }]
          : []),
        { sep: true },
        { icon: "move", label: "이동", onClick: () => setMoveTarget({ id: node.id, name: node.title || "제목 없음" }) },
        { icon: "edit", label: "이름 변경", onClick: () => setRenamingId(node.id) },
        { icon: "trash", label: "삭제", danger: true, onClick: () => removeNode(node.id) },
      ];
    }
    openMenu(x, y, items);
  };
```

(`findNode`는 App에 이미 import되어 있다 — `nodeName`에서 사용 중.)

- [ ] **Step 7: App — ROOT_DROP 잔재 정리**

`frontend/src/App.tsx`에서:
1. `const ROOT_DROP = "__ROOT__";` 줄 제거.
2. `onNodeDragOver`의 `setDragOverId(targetId ?? ROOT_DROP);` → `setDragOverId(targetId);`
3. `onNodeDragLeave` 본문을 아래로 교체:
```js
  const onNodeDragLeave = (targetId: string | null) => {
    setDragOverId((cur) => (cur === targetId ? null : cur));
  };
```
(이제 Sidebar는 폴더 row에서만 핸들러를 호출하므로 `targetId`는 항상 폴더 id. `canDropOn`이 `null`을 거부하므로 dragOver는 유효 폴더에서만 상태를 세팅.)

- [ ] **Step 8: CSS — `.tree.root-drop` 규칙 제거**

`frontend/src/styles/app.css`에서 아래 한 줄을 삭제:
```css
.tree.root-drop { box-shadow: inset 0 0 0 2px var(--ink); border-radius: var(--radius); }
```

- [ ] **Step 9: 타입체크/빌드 + 전체 스위트 확인**

Run: `pnpm build && pnpm test`
Expected: tsc 에러 0, dist 생성. 전체 테스트 스위트 그린(기존 테스트 + Task 1 dnd 테스트 — 단, Task 1 미병합 워크트리에서는 기존 dnd 테스트가 남아있어도 그린).

- [ ] **Step 10: 커밋**

```bash
git add frontend/src/components/Sidebar.tsx frontend/src/App.tsx frontend/src/styles/app.css
git commit -m "feat(sidebar): 최상위 폴더 드래그 잠금·단일 폴더 하이라이트·툴바 생성 제거·루트 생성 게이팅"
```

---

## 통합 검증 (오케스트레이터, 3개 Task 병합 후)

- [ ] **빌드/테스트:** `cd frontend && pnpm build && pnpm test` → tsc 0, 전체 스위트 그린.
- [ ] **QA (수동 또는 gstack):**
  - depth-0 폴더는 드래그되지 않음(잡히지 않음).
  - depth-0 노트·중첩 노드를 폴더 위로 드래그하면 **그 폴더만** 하이라이트(`.drop-target`), 사이드바 전체 테두리 없음.
  - 폴더에 드롭 시 이동 성공. 폴더 밖(빈 영역)에 드롭하면 무동작(원위치).
  - 우클릭 "이동"(MoveModal)에 "루트(최상위)" 옵션 없음. depth-0 폴더 우클릭 메뉴에 "이동" 없음.
  - 사이드바 상단 생성 툴바 없음. 폴더 우클릭 → "새 노트/폴더" 동작.
  - http 모드 일반 사용자: 빈 영역 우클릭 무반응. 관리자/local: 빈 영역 우클릭 → 루트 생성 메뉴.
