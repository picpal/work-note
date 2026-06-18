import { newId } from "./id";
import type { VaultTree, VaultNode, FolderNode, NoteNode } from "../types";

// walk: cb(node, parent, depth, pathArr)
function walk(
  tree: VaultTree,
  cb: (n: VaultNode, parent: FolderNode | null, depth: number, path: string[]) => void,
  parent: FolderNode | null = null,
  depth = 0,
  path: string[] = [],
): void {
  for (const node of tree) {
    cb(node, parent, depth, path);
    if (node.type === "folder" && node.children) {
      walk(node.children, cb, node, depth + 1, path.concat(node.name));
    }
  }
}

export function walkTree(
  tree: VaultTree,
  cb: (n: VaultNode, parent: FolderNode | null, depth: number, path: string[]) => void,
): void {
  walk(tree, cb);
}

export function findNode(
  tree: VaultTree,
  id: string,
): { node: VaultNode | null; parentArr: VaultTree | null; parentNode: FolderNode | null; path: string[] } {
  let found: VaultNode | null = null;
  let parentArr: VaultTree | null = null;
  let parentNode: FolderNode | null = null;
  let path: string[] = [];
  walk(tree, (n, parent, _d, p) => {
    if (n.id === id) {
      found = n;
      parentNode = parent;
      parentArr = parent ? parent.children : tree;
      path = p;
    }
  });
  return { node: found, parentArr, parentNode, path };
}

// 브레드크럼용 조상 폴더 체인(루트→부모 순). 각 세그먼트의 id를 함께 반환 — 링크 네비게이션용.
// 노트/폴더 자신은 제외, 조상 폴더만. 루트 직속이거나 못 찾으면 빈 배열.
export function crumbPath(tree: VaultTree, id: string): Array<{ id: string; name: string }> {
  let chain: Array<{ id: string; name: string }> = [];
  const dfs = (nodes: VaultTree, trail: Array<{ id: string; name: string }>): boolean => {
    for (const n of nodes) {
      if (n.id === id) { chain = trail; return true; }
      if (n.type === "folder" && dfs(n.children, trail.concat({ id: n.id, name: n.name }))) return true;
    }
    return false;
  };
  dfs(tree, []);
  return chain;
}

// 폴더 내 '첫 번째' 노트 — 직속 노트 우선, 없으면 하위 폴더를 순서대로 깊이우선.
// 브레드크럼에서 폴더 세그먼트 클릭 시 열어줄 노트. 노트가 전혀 없으면 null.
export function firstNoteIn(folder: FolderNode): NoteNode | null {
  const kids = folder.children || [];
  const direct = kids.find((n) => n.type === "note");
  if (direct) return direct as NoteNode;
  for (const k of kids) {
    if (k.type === "folder") {
      const hit = firstNoteIn(k);
      if (hit) return hit;
    }
  }
  return null;
}

// --- structural sharing helpers ---

// Walks nodes, copying only the path to the target id. Returns null if id not found.
function updateAt(nodes: VaultTree, id: string, mutate: (n: VaultNode) => void): VaultTree | null {
  let changed = false;
  const next = nodes.map((n) => {
    if (n.id === id) {
      // Copy the target node. For folders, also spread children so mutate
      // operates on a fresh array — prevents accidental shared-children mutation.
      const copy: VaultNode = n.type === "folder" ? { ...n, children: [...n.children] } : { ...n };
      mutate(copy);
      changed = true;
      return copy;
    }
    if (n.type === "folder") {
      const sub = updateAt(n.children, id, mutate);
      if (sub) { changed = true; return { ...n, children: sub }; }
    }
    return n;  // untouched: same reference
  });
  return changed ? next : null;
}

// returns NEW tree with mutator applied to node of given id
export function updateNode(tree: VaultTree, id: string, mutate: (n: VaultNode) => void): VaultTree {
  return updateAt(tree, id, mutate) ?? tree;
}

// Walks nodes, copying only the path to the target folder. Returns null if folderId not found.
function insertAt(nodes: VaultTree, folderId: string, child: VaultNode): VaultTree | null {
  let changed = false;
  const next = nodes.map((n) => {
    if (n.id === folderId && n.type === "folder") {
      changed = true;
      return { ...n, open: true, children: [...(n.children || []), child] };
    }
    if (n.type === "folder") {
      const sub = insertAt(n.children, folderId, child);
      if (sub) { changed = true; return { ...n, children: sub }; }
    }
    return n;  // untouched: same reference
  });
  return changed ? next : null;
}

// insert child into folder (id) or root if id == null
export function insertChild(tree: VaultTree, folderId: string | null, child: VaultNode): VaultTree {
  if (folderId == null) return [...tree, child];
  return insertAt(tree, folderId, child) ?? tree;
}

// Walks nodes, copying only the path to the first matching id. Returns null if id not found.
function removeAt(nodes: VaultTree, id: string): VaultTree | null {
  const idx = nodes.findIndex((n) => n.id === id);
  if (idx >= 0) {
    return [...nodes.slice(0, idx), ...nodes.slice(idx + 1)];
  }
  let changed = false;
  const next = nodes.map((n) => {
    if (n.type === "folder") {
      const sub = removeAt(n.children, id);
      if (sub) { changed = true; return { ...n, children: sub }; }
    }
    return n;  // untouched: same reference
  });
  return changed ? next : null;
}

export function removeNode(tree: VaultTree, id: string): VaultTree {
  return removeAt(tree, id) ?? tree;
}

// id가 ancestorId 자신 또는 그 자손인지 (폴더 자손만 — 노트는 자식 없음)
export function isSelfOrDescendant(tree: VaultTree, ancestorId: string, id: string): boolean {
  if (ancestorId === id) return true;
  const { node } = findNode(tree, ancestorId);
  if (!node || node.type !== "folder") return false;
  let hit = false;
  walkTree(node.children, (n) => { if (n.id === id) hit = true; });
  return hit;
}

// 이동: 자기 자신/자손 폴더로는 금지(무변경), 그 외 removeNode + insertChild
export function moveNode(tree: VaultTree, id: string, newParentId: string | null): VaultTree {
  if (newParentId != null && isSelfOrDescendant(tree, id, newParentId)) return tree;
  const { node } = findNode(tree, id);
  if (!node) return tree;
  return insertChild(removeNode(tree, id), newParentId, node);
}

// 이동 대상 후보 폴더(자신·자손 제외). 라벨은 "A / B / C" 경로. 루트는 호출측에서 별도 추가.
export function folderOptions(tree: VaultTree, excludeId: string): Array<{ id: string; label: string; isRoot: boolean }> {
  const out: Array<{ id: string; label: string; isRoot: boolean }> = [];
  walkTree(tree, (n, _p, depth, path) => {
    if (n.type !== "folder") return;
    if (isSelfOrDescendant(tree, excludeId, n.id)) return;
    out.push({ id: n.id, label: path.concat(n.name).join(" / "), isRoot: depth === 0 });
  });
  return out;
}

// 폴더 아이콘 선택: 최상위(depth 0)는 스페이스, 그 외는 펼침 상태에 따라 folder/folderOpen.
export function folderIconName(depth: number, open: boolean): "users" | "folderOpen" | "folder" {
  if (depth === 0) return "users"; // 루트 폴더 = 팀 스페이스 → 관리자 화면과 동일한 사람 아이콘
  return open ? "folderOpen" : "folder";
}

// 사이드바 표시 정렬 키. 표시 전용이라 새로고침하면 기본값(name-asc)으로 돌아감.
export type TreeSortKey = "name-asc" | "name-desc" | "created-asc" | "created-desc";

function nodeName(n: VaultNode): string {
  return n.type === "folder" ? n.name : n.title;
}

// 사이드바 표시 정렬: 폴더 먼저 → 노트, 각 그룹은 선택 키 기준.
// 이름은 숫자 자연순(ko), 생성일은 ISO 문자열 사전순. 동일 생성일은 이름으로 안정 정렬.
// 폴더 우선 그룹화는 방향·키와 무관하게 항상 유지. 표시 전용 — 원본 배열 불변(복사 후 정렬).
export function sortTreeNodes(nodes: VaultTree, key: TreeSortKey = "name-asc"): VaultTree {
  const dir = key.endsWith("-desc") ? -1 : 1;
  const byCreated = key.startsWith("created");
  return [...nodes].sort((a, b) => {
    if (a.type !== b.type) return a.type === "folder" ? -1 : 1;
    if (byCreated) {
      const ac = a.created || "", bc = b.created || "";
      if (ac !== bc) return dir * ac.localeCompare(bc);   // ISO 문자열 비교
      // 동일 생성일시 → 이름 오름차순 tie-break(방향과 무관하게 안정적)
      return nodeName(a).localeCompare(nodeName(b), "ko", { numeric: true, sensitivity: "base" });
    }
    return dir * nodeName(a).localeCompare(nodeName(b), "ko", { numeric: true, sensitivity: "base" });
  });
}

// flatten all notes with their folder path, for search
export function flattenNotes(tree: VaultTree): Array<{ note: NoteNode; path: string[] }> {
  const out: Array<{ note: NoteNode; path: string[] }> = [];
  walk(tree, (n, _parent, _d, p) => {
    if (n.type === "note") out.push({ note: n, path: p });
  });
  return out;
}

// count notes inside a folder (recursive)
export function countNotes(folder: FolderNode): number {
  let c = 0;
  walk(folder.children || [], (n) => { if (n.type === "note") c++; });
  return c;
}

// reassign duplicate/missing ids so every node id is unique (repairs older vaults)
export function dedupeIds(tree: VaultTree): VaultTree {
  const seen = new Set<string>();
  const walkDedup = (nodes: VaultTree) => {
    nodes.forEach((n) => {
      if (!n.id || seen.has(n.id)) n.id = newId();
      seen.add(n.id);
      if (n.type === "folder" && n.children) walkDedup(n.children);
    });
  };
  walkDedup(tree);
  return tree;
}
