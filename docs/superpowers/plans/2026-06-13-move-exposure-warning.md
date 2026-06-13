# 이동 노출 변경 경고 (§7) 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 노트/폴더를 다른 폴더로 이동할 때 접근 집합(노출)이 바뀌면 사용자에게 경고하고(스펙 §4.3 move 행 + §7), 공개 노출 시작·cross-space 이동은 강한 경고로 표시한다. 이동 UI 자체가 프런트에 없으므로 **컨텍스트 메뉴 이동 + 폴더 피커 + 경고 모달**을 풀스택으로 신설한다.

**Architecture:** 백엔드는 `com.worknote.acl.ExposureService`(이동 전/후 grant 스냅샷 델타 계산) + `GET /api/nodes/{id}/move-preview` 엔드포인트 + 이동 감사 보강. 프런트는 tree 헬퍼(`moveNode`/`folderOptions`) + reducer/useVault/useVaultSync move 배선 + VaultApi.movePreview + MoveModal(피커→경고) + 컨텍스트 메뉴 항목.

**Tech Stack:** Java 21 + Spring Boot 3.5 + MyBatis + SQLite / Vite 6 + TS + React 18, Vitest(node env + fetch stub).

---

## 확정 결정

| # | 결정 | 근거 |
|---|---|---|
| M1 | 이동 UI = **컨텍스트 메뉴 "이동" → 폴더 피커 모달**(드래그앤드롭 아님) | dnd 라이브러리 불필요, 결정적·테스트 용이 (사용자 선택) |
| M2 | 노출 경고 preview는 **http 모드 전용** 호출 — local 모드는 피커 선택 즉시 이동 | local=단일 사용자, ACL/팀 없음 — 노출 개념 무의미 (ShareModal S13과 동일 결) |
| M3 | preview 엔드포인트 권한 = 이동과 동일(`requireMove` = edit(원본)∧edit(대상)) | 이동 못 하면 미리보기도 못 함 — 일관 |
| M4 | 노출 경고는 **차단이 아니라 경고** — 사용자가 [이동] 누르면 진행 | 스펙: 이동은 edit∧edit이면 허용, 노출은 경고일 뿐 |
| M5 | grant 스냅샷 = 체인의 ACL nearest-explicit(주체별, deny-sticky) **allow 집합** + `publicRead` 별도 | AclResolver 기존 의미론 재사용 — enforce 경로와 동일 |
| M6 | 델타: `added`/`removed`(ACL 상속으로 read/edit 획득·상실한 주체 라벨), `publicBefore/After`, `crossSpace`+`fromSpace`/`toSpace`(팀명) | 스펙 "접근 집합 변경" + "cross-space 강한 경고" 둘 다 |
| M7 | 폴더 이동은 **폴더 노드 레벨** 노출로 판정(서브트리 개별 override 미열거) | 폴더 public/ACL은 자손에 캐스케이드 — 폴더 레벨 신호가 1차 정확 |
| M8 | cross-space = 이동 전/후 **최상위 조상의 소유 팀(space)** 이 다름(null=공용/무소유도 별개 값) | 스페이스 = 최상위 폴더↔팀 (스펙 §4.2) |
| M9 | 이동 감사 보강: 노출 변경 시 target에 압축 접미사 부기(`id -> pid [공개노출↑]` 등), act는 기존 `node.move` 재사용 | §7 감사 재구성, 프런트 confirm과 무관하게 서버가 사실 기록 |
| M10 | preview 검증은 이동과 동일(미존재 404 / 폴더 아님·사이클 422) | 프런트가 시도 전 동일 오류를 받음 |
| M11 | 주체 라벨: 팀→팀명, `@all`→"전 직원", user→사번(emp) | 사람이 읽는 경고 문구 |
| M12 | `shouldWarn`은 프런트 **순수 함수**(`{warn, strong, lines}`) — 테스트로 고정 | UI는 배선, 판정 로직은 단위 테스트 |

## API 계약 (신규 1 엔드포인트)

| Method | Path | 권한 | 성공 | 비고 |
|---|---|---|---|---|
| GET | `/api/nodes/{id}/move-preview?parentId={pid}` | `requireMove`(edit∧edit) | 200 `MovePreview` | parentId 생략=루트. 미존재 404, 폴더 아님·사이클 422 |

```
MovePreview = {
  publicBefore: boolean, publicAfter: boolean,
  crossSpace: boolean, fromSpace: string|null, toSpace: string|null,
  added: string[],    // ACL 상속으로 read/edit 획득한 주체 라벨
  removed: string[]   // 상실한 주체 라벨
}
```
`accessChanged`/severity는 프런트 `shouldWarn`이 파생. local 모드 preview는 안전하게 호출 가능하나 보통 빈 결과.

---

## Task 1: ExposureService + MovePreview (백엔드, 계산)

**Files:**
- Create: `backend/src/main/java/com/worknote/acl/MovePreview.java`
- Create: `backend/src/main/java/com/worknote/acl/ExposureService.java`
- Test: `backend/src/test/java/com/worknote/acl/ExposureServiceTest.java`

설계:
- `MovePreview` record: `(boolean publicBefore, boolean publicAfter, boolean crossSpace, String fromSpace, String toSpace, List<String> added, List<String> removed)`.
- `ExposureService.preview(String nodeId, String newParentId)`:
  1. `chainBefore = acl.ancestorChain(nodeId)` (자신→루트). 비면 노드 미존재.
  2. `chainAfter`: 이동 후 가상 체인 = `[nodeId] + (newParentId==null ? [] : acl.ancestorChain(newParentId))`. (newParentId의 체인은 그 부모를 포함하므로 nodeId만 앞에 붙임.)
  3. 각 체인에 대해 `grantSnapshot(chain)`:
     - `acl.findAclForNodes(chain)` → 주체별(`type:id`) `nodeId→grant` 맵 → `AclResolver.nearestExplicit(chain, perPrincipal)`.
     - `allow = { principal : nearest in (read,edit) }`. (deny는 제외 — 노출은 "읽게 됨"이 관심.)
     - `public = AclResolver.publicRead(chain, flagMap(acl.findPublicFlagsForNodes(chain)))`.
  4. `added = label(allowAfter - allowBefore)`, `removed = label(allowBefore - allowAfter)` (정렬: 팀 먼저, @all, user — 결정적).
  5. 스페이스: `topLevel(chain) = chain.get(chain.size()-1)`. `spaceTeam(top) = space.find(top)?.teamId`. `fromSpace`/`toSpace` = 팀명(teamId→name, null이면 null). `crossSpace = !Objects.equals(fromTeamId, toTeamId)` (단, chainAfter가 nodeId만이면[루트로 이동] top=nodeId 자신 → 그 노드가 space 보유 시 그것, 아니면 null).
  6. 라벨(M11): TeamMapper.findAll()→id→name 맵, `all:@all`→"전 직원", `user:{id}`→ UserMapper로 emp 조회(소수라 findById 허용. 적절한 배치 메서드 있으면 사용).
- 의존성: `AclMapper acl, SpaceMapper space, TeamMapper teams, UserMapper users`. 순수 계산은 AclResolver 재사용.

- [ ] **Step 1: 실패 테스트 작성** — 기존 백엔드 테스트 컨벤션(@SpringBootTest + `properties="spring.datasource.url=jdbc:sqlite:file::memory:?cache=shared"`, @BeforeEach 정리, JdbcTemplate 또는 매퍼로 시드). `mv-` 접두 노드. 케이스:
  1. **공개 노출 시작**: 비공개 노트(public flag 없음)를 public 폴더 아래로 이동 → `publicBefore=false, publicAfter=true`.
  2. **공개 노출 종료**: public 폴더 안 노트를 비공개 폴더로 → `publicBefore=true, publicAfter=false`.
  3. **ACL 상속 추가**: 대상 폴더에 팀T edit grant가 있으면 이동 후 `added`에 팀T 라벨 포함.
  4. **ACL 상실**: 원본 폴더에 팀T grant가 있고 대상엔 없으면 `removed`에 팀T.
  5. **cross-space**: 서로 다른 최상위(소유 팀 A→B) 간 이동 → `crossSpace=true, fromSpace="A팀", toSpace="B팀"`. 같은 최상위 내 이동 → `crossSpace=false`.
  6. **변경 없음**: 같은 부모 형제 폴더(동일 grant·public)로 이동 → added/removed 비고 publicBefore==publicAfter.
  7. **루트로 이동**: newParentId=null → chainAfter=[node]만, public/space 자기 자신 기준.
- [ ] **Step 2: red 확인** — `cd backend && ./gradlew test --tests ExposureServiceTest`
- [ ] **Step 3: 구현** (위 설계)
- [ ] **Step 4: green + 전체 회귀** — `./gradlew test` (현재 230 + 신규)
- [ ] **Step 5: 커밋** — `git add backend/src && git commit -m "feat: 이동 노출 델타 계산 ExposureService (스펙 §7)"`

## Task 2: move-preview 엔드포인트 + 이동 감사 보강 (백엔드)

**Files:**
- Modify: `backend/src/main/java/com/worknote/vault/VaultController.java` (move-preview GET + move 감사 보강)
- Test: `backend/src/test/java/com/worknote/vault/MovePreviewApiTest.java` (server 모드 MockMvc)

설계:
- `@GetMapping("/nodes/{id}/move-preview")` `view(@PathVariable id, @RequestParam(required=false) String parentId, req)`:
  - `UserRow user = user(req); guard.requireMove(user, id, parentId);`
  - 검증: `svc`에 이동 가능성 검증을 재사용할 수 있으면 사용(미존재 404, 폴더 아님·사이클 422). 별도 검증 메서드가 없으면 ExposureService 호출 전 `nodes.findById`·타입·`subtreeIds` 사이클 체크를 가드 뒤에 수행(이동과 동일 메시지). 
  - `return exposure.preview(id, parentId);`
- move 감사 보강: 기존
  ```java
  guard.requireMove(user, id, body.parentId());
  svc.move(id, body.parentId());
  audit.log(user, "node.move", id + " -> " + (parent or root), ip);
  ```
  를 → 이동 **전** `MovePreview p = exposure.preview(id, body.parentId());` 계산, mutate, 감사 target에 접미사:
  - `suffix`: `p.publicAfter()&&!p.publicBefore()` → " [공개노출 시작]"; `p.crossSpace()` → " [cross-space: "+fromSpace+"→"+toSpace+"]"; 그 외 added/removed 있으면 " [접근주체 변경]". 여러 개면 이어붙임. 변경 없으면 접미사 없음.
  - ExposureService를 VaultController에 주입.
- MockMvc 케이스(기존 server 모드 테스트 셋업 재사용 — 사용자/역할/팀/ACL 시드 헬퍼):
  1. 운영자가 자기 edit 가능한 노트를 다른 edit 가능 폴더로 move-preview → 200, 필드 존재.
  2. 비공개 노트→public 폴더 preview → `publicAfter=true, publicBefore=false`.
  3. edit 불가(deny) 노트 preview → 403.
  4. 폴더를 자기 자손으로 preview → 422.
  5. 실제 move 후 audit_log target에 `[공개노출 시작]` 접미사 기록(비공개→public 케이스).
  6. 변경 없는 이동 → 접미사 없는 `id -> pid` 그대로.
- [ ] Step 1 red 테스트 → Step 2 red 확인(`--tests MovePreviewApiTest`) → Step 3 구현 → Step 4 `./gradlew test` ×1 → Step 5 커밋 `feat: move-preview 엔드포인트 + 이동 노출 감사 보강 (스펙 §7)`

## Task 3: 프런트 이동 배관 (tree/reducer/useVault/useVaultSync)

**Files:**
- Modify: `frontend/src/lib/tree.ts` (moveNode, folderOptions, isDescendant)
- Modify: `frontend/src/state/vaultReducer.ts` (move 액션)
- Modify: `frontend/src/state/useVault.ts` (move 액션 생성자)
- Modify: `frontend/src/state/useVaultSync.ts` (synced.move)
- Test: `frontend/src/lib/tree.test.ts`, `frontend/src/state/vaultReducer.test.ts`, `frontend/src/state/useVaultSync.test.ts`

설계:
- `tree.ts`:
  ```typescript
  // id가 maybeAncestorId의 자손(또는 자신)인지
  export function isSelfOrDescendant(tree: VaultTree, ancestorId: string, id: string): boolean {
    if (ancestorId === id) return true;
    const { node } = findNode(tree, ancestorId);
    if (!node || node.type !== "folder") return false;
    let hit = false;
    walkTree(node.children, (n) => { if (n.id === id) hit = true; });
    return hit;
  }
  // 이동: 자기 자신/자손 폴더로는 금지(트리 보존), 그 외 removeNode + insertChild
  export function moveNode(tree: VaultTree, id: string, newParentId: string | null): VaultTree {
    if (newParentId != null && isSelfOrDescendant(tree, id, newParentId)) return tree;
    const { node } = findNode(tree, id);
    if (!node) return tree;
    return insertChild(removeNode(tree, id), newParentId, node);
  }
  // 이동 대상 후보 폴더(자신·자손 제외) — 라벨은 경로. 루트는 호출측에서 별도 추가.
  export function folderOptions(tree: VaultTree, excludeId: string): Array<{ id: string; label: string }> {
    const out: Array<{ id: string; label: string }> = [];
    walkTree(tree, (n, _p, _d, path) => {
      if (n.type !== "folder") return;
      if (isSelfOrDescendant(tree, excludeId, n.id)) return;  // 자신·자손 제외
      out.push({ id: n.id, label: path.concat(n.name).join(" / ") });
    });
    return out;
  }
  ```
- `vaultReducer.ts`: 액션 `| { type: "move"; id: string; parentId: string | null }`, case → `return moveNode(tree, a.id, a.parentId);` (import moveNode).
- `useVault.ts`: `move: (id: string, parentId: string | null) => dispatch({ type: "move", id, parentId })`.
- `useVaultSync.ts`: synced 객체에 `move: (id, parentId) => { actionsRef.current.move(id, parentId); fire({ kind: "move", id, parentId }); }`. (syncAction의 move 케이스는 이미 존재 — 주석 "매핑만 준비 (UI 없음)"는 "이동 UI 배선" 정도로 갱신.)
- 테스트:
  - tree.test.ts: moveNode(노트를 폴더로/폴더 이동/루트로/자신 거부/자손 거부), isSelfOrDescendant, folderOptions(자신·자손 제외·루트 미포함·경로 라벨).
  - vaultReducer.test.ts: move 액션이 노드를 옮긴다 + 자손 이동은 무변경.
  - useVaultSync.test.ts: 기존 "maps move to move" 유지 + (가능하면) synced.move가 actions.move 호출 + move op fire 단언(기존 패턴대로).
- [ ] Step 1 red 테스트 → Step 2 `pnpm test` red → Step 3 구현 → Step 4 green(현재 100 + 신규) → Step 5 커밋 `feat: 프런트 이동 배관 — moveNode·folderOptions·reducer·sync`

## Task 4: VaultApi.movePreview + MoveModal + 컨텍스트 메뉴 (프런트)

**Files:**
- Modify: `frontend/src/storage/VaultApi.ts` (movePreview + MovePreview 타입)
- Create: `frontend/src/components/MoveModal.tsx`
- Create: `frontend/src/components/moveWarning.ts` (shouldWarn 순수 함수)
- Test: `frontend/src/components/moveWarning.test.ts`, `frontend/src/storage/VaultApi.test.ts`(있으면 확장, 없으면 신규)
- Modify: `frontend/src/App.tsx` (컨텍스트 메뉴 "이동" + MoveModal 상태/렌더)

설계:
- `VaultApi.ts`:
  ```typescript
  export interface MovePreview {
    publicBefore: boolean; publicAfter: boolean;
    crossSpace: boolean; fromSpace: string | null; toSpace: string | null;
    added: string[]; removed: string[];
  }
  // AdminApi.qs와 동일한 생략 규칙 — parentId null이면 쿼리 생략(루트)
  movePreview: (id: string, parentId: string | null) =>
    req<MovePreview>(`/nodes/${id}/move-preview` + (parentId != null ? `?parentId=${encodeURIComponent(parentId)}` : "")),
  ```
- `moveWarning.ts`:
  ```typescript
  import type { MovePreview } from "../storage/VaultApi";
  export interface WarnResult { warn: boolean; strong: boolean; lines: string[]; }
  export function shouldWarn(p: MovePreview): WarnResult {
    const lines: string[] = [];
    let strong = false;
    if (p.publicAfter && !p.publicBefore) { lines.push("이 위치에서는 전 직원이 읽을 수 있게 됩니다 (공개 노출)."); strong = true; }
    if (!p.publicAfter && p.publicBefore) { lines.push("더 이상 공개 노출되지 않습니다."); }
    if (p.crossSpace) { lines.push("다른 팀 스페이스로 이동합니다" + (p.fromSpace || p.toSpace ? ` (${p.fromSpace ?? "공용"} → ${p.toSpace ?? "공용"})` : "") + "."); strong = true; }
    if (p.added.length) lines.push("새로 접근 가능: " + p.added.join(", ") + ".");
    if (p.removed.length) lines.push("접근 해제: " + p.removed.join(", ") + ".");
    return { warn: lines.length > 0, strong, lines };
  }
  ```
- `MoveModal.tsx` (ProfileModal 마크업·클래스 pf-* 재사용, h=createElement):
  - props `{ node: {id, name}, tree, onMove: (id, parentId)=>void, onClose, toast }`.
  - 상태: `target: {id|null}` 선택, `phase: "pick" | "warn"`, `preview: MovePreview|null`, `busy`.
  - 피커: `folderOptions(tree, node.id)` + 맨 위 "루트(최상위)" 옵션(id=null). 라디오/리스트. 현재 부모로의 이동은 비활성/숨김(선택 의미 없음 — 선택 가능하되 동일 위치면 그냥 닫기 처리).
  - [이동] 클릭: http 모드면 `VaultApi.movePreview(node.id, target)` → `shouldWarn`:
    - warn=false → `onMove(node.id, target)` + 토스트 "이동했습니다" + close.
    - warn=true → phase="warn"로 전환, 경고 lines 표시(strong이면 위험 스타일), [취소]/[이동(확인)].
    - 실패(403/422) → `e instanceof ApiError ? e.message : "이동할 수 없습니다"` 토스트, pick 유지.
    - local 모드(storageMode!=="http") → preview 생략, 즉시 `onMove`+close.
  - warn phase의 [이동] → `onMove(node.id, target)` + 토스트 + close.
- `App.tsx`:
  - import MoveModal, `storageMode` 이미 있음.
  - 상태 `const [moveNode, setMoveNode] = useState<{id:string;name:string}|null>(null);` (이름 충돌 주의 — lib/tree의 moveNode import와 구분: 상태명을 `moveTarget`으로).
  - onContext folder 분기·note 분기 모두에 "이름 변경" 위/근처에 `{ icon: "move", label: "이동", onClick: () => setMoveTarget({ id: node.id, name: node.type==="folder"? node.name : node.title }) }` (icon "move" 없으면 Icon.tsx에 화살표 아이콘 추가).
  - 렌더: `moveTarget && h(MoveModal, { node: moveTarget, tree, onMove: actions.move, onClose: () => setMoveTarget(null), toast })`.
- 테스트:
  - moveWarning.test.ts: 공개 노출 시작(strong), 공개 해제(warn·약), cross-space(strong), added/removed(warn), 변경 없음(warn=false). lines 내용 단언.
  - VaultApi.test.ts: movePreview 경로(parentId 있을 때 `?parentId=...`, null이면 쿼리 없음, 특수문자 인코딩).
- [ ] Step 1 red 테스트 → Step 2 `pnpm test` red → Step 3 구현 → Step 4 `pnpm test` green + `pnpm build` → Step 5 커밋 `feat: 이동 폴더 피커 + 노출 경고 모달 + 컨텍스트 메뉴`

## Task 5: 통합 검증 + 문서 갱신

**Files:** `backend/README.md`, `CLAUDE.md`, 이 플랜 체크박스, 메모리.

- [ ] **Step 1: 전체 테스트** — `cd backend && ./gradlew test && ./gradlew test`, `cd frontend && pnpm test && pnpm test` 각 2회 green.
- [ ] **Step 2: 빌드 + jar 스모크** — `pnpm build` → `./gradlew bootJar` → server 모드 jar:
  1. admin 로그인 → 200
  2. 폴더 A(public 토글)·폴더 B 생성, 노트 N을 B 아래 생성
  3. GET `/api/nodes/N/move-preview?parentId={A}` → publicAfter 확인
  4. POST `/api/nodes/N/move {parentId:A}` → 204
  5. GET `/api/admin/audit?act=node.move` → target에 `[공개노출 시작]` 접미사 확인
  6. 사이클 preview(폴더를 자기 자손으로) → 422
  + local 모드: 노트 생성→move-preview(빈 결과여도 200)→move 204.
- [ ] **Step 3: 문서 + 메모리** — README API 표에 move-preview 추가, 이월 목록에서 "이동 노출 변경 경고(§7)" 제거, CLAUDE.md 갱신, 플랜 체크박스 [x], 메모리 신규 파일 + MEMORY.md 인덱스. 커밋 `docs: 이동 노출 변경 경고 완료 반영 (§7)`.

---

## 이월 후보 (이번 범위 제외)

- 폴더 이동 시 서브트리 노드별 개별 override 열거(현재 폴더 레벨 신호만 — M7)
- 드래그앤드롭 이동 UX(현재 컨텍스트 메뉴 피커)
- 4단계 이월 3건(401 patch 유실/시드 fallback 배너/본인 비밀번호 변경 API)
- 공유 링크 만료·취소 행 정리 배치, pin 사번 존재 검증, 실패 열람 감사
