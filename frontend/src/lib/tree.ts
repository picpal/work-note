import { newId } from "./id";
import type { VaultTree, VaultNode, FolderNode, NoteNode } from "../types";

// deep clone (data is plain JSON-able)
const clone = <T>(t: T): T => JSON.parse(JSON.stringify(t));

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

// returns NEW tree with mutator applied to node of given id
export function updateNode(tree: VaultTree, id: string, mutate: (n: VaultNode) => void): VaultTree {
  const t = clone(tree);
  const { node } = findNode(t, id);
  if (node) mutate(node);
  return t;
}

// insert child into folder (id) or root if id == null
export function insertChild(tree: VaultTree, folderId: string | null, child: VaultNode): VaultTree {
  const t = clone(tree);
  if (folderId == null) { t.push(child); return t; }
  const { node } = findNode(t, folderId);
  if (node && node.type === "folder") {
    node.open = true;
    node.children = node.children || [];
    node.children.push(child);
  }
  return t;
}

export function removeNode(tree: VaultTree, id: string): VaultTree {
  const t = clone(tree);
  function rec(arr: VaultTree): boolean {
    const i = arr.findIndex((n) => n.id === id);
    if (i >= 0) { arr.splice(i, 1); return true; }
    for (const n of arr) if (n.type === "folder" && n.children && rec(n.children)) return true;
    return false;
  }
  rec(t);
  return t;
}

// flatten all notes with their folder path, for search
export function flattenNotes(tree: VaultTree): Array<{ note: NoteNode; path: string[] }> {
  const out: Array<{ note: NoteNode; path: string[] }> = [];
  walk(tree, (n, _parent, _d, p) => {
    if (n.type === "note") out.push({ note: n as NoteNode, path: p });
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
