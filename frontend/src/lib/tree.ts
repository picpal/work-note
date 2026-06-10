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
