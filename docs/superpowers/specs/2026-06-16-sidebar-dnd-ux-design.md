# 사이드바 DnD UX 개선 — 설계

- 날짜: 2026-06-16
- 범위: **A. 사이드바 드래그앤드롭 UX 수정 (프런트엔드 전용)**
- 제외: B. 관리자 루트 순서변경 (별도 스펙 — 본 문서 §6 참조)

## 1. 배경 / 문제

사이드바 트리에서 최상위를 포함한 모든 노드를 DnD로 이동할 수 있고, 이동 위치 표시가 어색하다.

- **사이드바 전체에 포커스가 잡힘**: 폴더 위로 드래그해도 폴더가 아니라 사이드바(`.tree`) 전체에 테두리가 켜진다.
- 최상위 디렉토리(스페이스)도 자유롭게 이동되어 구조가 흐트러진다.
- 노트/폴더를 폴더 밖(루트)으로도 끌어낼 수 있어 의도치 않은 최상위 노출이 생긴다.

### 근본 원인 (코드 확인)

`Sidebar.tsx`의 `.tree` 컨테이너가 `onDragOver(targetId=null)` 핸들러를 가진다. DnD 이벤트는 폴더 row(타깃)에서 먼저 발생해 `dragOverId = 폴더id`를 설정하지만, **버블링되어 `.tree` 핸들러가 마지막에 실행**되며 `dragOverId = "__ROOT__"`로 덮어쓴다. 그 결과 항상 `.tree.root-drop`(전체 테두리)이 켜진다. (폴더 `onDrop`은 `stopPropagation`이 있어 드롭 자체는 폴더로 들어가지만, **시각 하이라이트만** 루트로 샌다.)

→ 루트 드롭을 제거하면 덮어쓰기와 전체 하이라이트가 동시에 사라진다.

## 2. 목표 (확정된 요구)

1. 노트·폴더는 **폴더 안에만** 드롭 가능 (루트로의 드롭/승격 없음).
2. 드래그 중 폴더 위로 올라오면 **그 폴더만** 포커스되어 목적지를 표시.
3. **depth-0 폴더**는 이동 불가(immovable). depth-0 노트는 폴더 안으로 이동 가능.
4. 루트(최상위) 생성은 **관리자(또는 local 모드)만**. 사이드바 상단 생성 툴바를 제거하고 **우클릭 생성만** 남긴다. 일반 사용자가 빈 영역(루트)을 우클릭하면 **무반응**.
5. 우클릭 "이동" 메뉴(MoveModal)에서도 "루트(최상위)" 옵션 제거.

## 3. 현재 구조 (기준점)

- `lib/dnd.ts` `canDropOn(tree, draggedId, targetId)`: `targetId===null`(루트) 또는 폴더 타깃 허용. 자기/자손·현재 부모 제외.
- `Sidebar.tsx`:
  - `Row`: `draggable = !renaming`. 폴더만 `dropProps`(over/leave/drop) 부여. 폴더 hover 시 `.drop-target`.
  - `.tree` 컨테이너: `onDragOver/onDragLeave/onDrop`(null=루트) + `dragOverId==="__ROOT__"`면 `.root-drop`.
  - `sb-toolbar`: "새 노트"·"새 폴더"·spacer·"모두 접기".
- `App.tsx`:
  - `onContext(x,y,node)`: node=null(빈 영역) → 새 노트/폴더(루트). 폴더 → 폴더 안 생성 + 이동/이름변경/삭제. 노트 → 내보내기/공유/이동/….
  - `useSession()`이 `isAdmin` 제공. `canCreateAtRoot = storageMode==='local' || isAdmin` (기존 `showAdmin` 로직과 동일 기준).
  - DnD 핸들러: `onNodeDragStart/Over/Leave/Drop/End`, `attemptDnDMove`(http 모드는 move-preview 노출 경고).
- `MoveModal.tsx`: "루트(최상위)" 버튼 + 폴더 옵션. `target=null`이 루트.
- 백엔드 `node.position` 컬럼·`parent_id, position, id` 정렬 **이미 존재**. move는 끝에 append. reorder 엔드포인트는 없음.

## 4. 설계 (변경 단위)

### 4.1 `lib/dnd.ts` — `canDropOn`
- `targetId === null`(루트) → `false` (루트 드롭 제거).
- 타깃은 활성 폴더만 (기존 self/descendant·현재 부모 제외 유지).
- 소스가 **depth-0 폴더면 `false`**(immovable): `dragged.parentNode == null && dragged.node.type === 'folder'`.
- → "이동 가능 여부"의 단일 판정원. (draggable=false는 1차 가드, canDropOn은 2차 가드.)

### 4.2 `Sidebar.tsx` — Row draggable
- `draggable = !renaming && !(isFolder && depth === 0)`.
- depth-0 폴더만 드래그 잠금. depth-0 노트는 드래그 가능.

### 4.3 `Sidebar.tsx` — `.tree` 루트 드롭 제거
- `.tree` div에서 `onDragOver / onDragLeave / onDrop` 및 `root-drop` 클래스 토글 제거 (`onContextMenu`는 유지).
- 폴더 row의 `.drop-target` 하이라이트만 남아 "그 폴더만 포커스" 달성.
- `ROOT_DROP`/루트 분기 정리.
- CSS `.tree.root-drop` 규칙 제거.

### 4.4 `Sidebar.tsx` — 생성 툴바 제거
- `sb-toolbar`에서 "새 노트"·"새 폴더" 버튼 제거. "모두 접기"는 유지.
- 그로 인해 미사용되는 `onNewNote` / `onNewFolder` props를 `SidebarProps`와 구조분해에서 제거.
- App의 `newNoteIn` / `newFolderIn`은 우클릭(onContext) 경로로 계속 사용.

### 4.5 `App.tsx` — `onContext` 루트 생성 게이팅
- `canCreateAtRoot = storageMode === 'local' || isAdmin`.
- **빈 영역(node=null)**:
  - `canCreateAtRoot` → "새 노트"·"새 폴더"(루트) 메뉴 (기존).
  - 그렇지 않으면 **메뉴를 열지 않고 즉시 return** (무반응, 토스트도 없음).
- **폴더(node.type==='folder')**: "새 노트"·"새 폴더"(폴더 안)는 누구나 (기존). 단 **depth-0 폴더면 "이동" 항목 제거**(immovable 일관성). `isTopFolder = findNode(tree, node.id).parentNode == null`.
- **노트**: 변경 없음.

### 4.6 `MoveModal.tsx` — 루트 옵션 제거
- "루트 (최상위)" 버튼 제거. 폴더 옵션만.
- `target`/`selected`/`pick(null)`의 루트 분기 정리 — 타깃은 폴더 id만.
- 선택 가능한 폴더가 없으면 "이동 가능한 폴더가 없습니다" 안내.
- (depth-0 폴더는 4.5에서 "이동" 메뉴가 사라지므로 MoveModal 진입 자체가 없음.)

### 4.7 테스트
- `lib/dnd.test.ts`: 루트 드롭 거부, depth-0 폴더 immovable, depth-0 노트→폴더 허용, 폴더→폴더 허용/자손 거부 케이스 갱신·추가.
- 필요 시 onContext 게이팅·MoveModal 루트 제거에 대한 컴포넌트 테스트 보강.

## 5. 결과 동작 매트릭스

| 대상 | 드래그 | 폴더에 드롭 | 루트로 | 우클릭 생성 |
|------|--------|------------|--------|------------|
| depth-0 폴더 | ❌ 잠금 | (드롭 타깃은 가능) | — | 폴더 안 생성 ✓ / "이동" 메뉴 없음 |
| depth-0 노트 | ✓ | ✓ | ❌ | — |
| 중첩 폴더/노트 | ✓ | ✓ | ❌ | 폴더 안 생성 ✓ |
| 루트 생성 | — | — | 관리자/local만 (빈영역 우클릭) | 일반 사용자 = 무반응 |

## 6. 제외 — B. 관리자 루트 순서변경 (별도 스펙)

- 백엔드: 최상위 형제(siblings) `position` 재정렬 엔드포인트 (컬럼은 이미 존재).
- 관리자 프런트: 루트 노드 순서변경 UI(상하 이동/드래그).
- 사이드바는 `parent_id, position, id` 정렬을 이미 따르므로 데이터가 바뀌면 자동 반영.

## 7. 엣지/주의

- 빈 vault: 첫 최상위 폴더는 관리자/local이 빈영역 우클릭으로 생성. 일반 사용자는 폴더가 생긴 뒤 그 안에서 우클릭 생성.
- http 모드 이동은 기존 `attemptDnDMove`(move-preview 노출 경고) 흐름 유지 — DnD 가드만 강화.
- DnD 가드(canDropOn) 강화로 잘못된 드롭은 무시(드래그 원위치 복귀).
