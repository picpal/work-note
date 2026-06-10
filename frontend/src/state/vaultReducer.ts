import type { VaultTree, VaultNode, NoteNode } from "../types";
import { walkTree, updateNode, insertChild, removeNode } from "../lib/tree";

const clone = <T>(t: T): T => JSON.parse(JSON.stringify(t));

export type VaultAction =
  | { type: "toggle"; id: string }
  | { type: "open"; id: string }
  | { type: "collapseAll" }
  | { type: "insert"; folderId: string | null; node: VaultNode }
  | { type: "rename"; id: string; value: string }
  | { type: "remove"; id: string }
  | { type: "updateNote"; id: string; patch: Partial<NoteNode> }
  | { type: "replace"; tree: VaultTree };

export function vaultReducer(tree: VaultTree, a: VaultAction): VaultTree {
  switch (a.type) {
    case "toggle":
      return updateNode(tree, a.id, (n) => { if (n.type === "folder") n.open = !n.open; });
    case "open":
      return updateNode(tree, a.id, (n) => { if (n.type === "folder") n.open = true; });
    case "collapseAll": {
      const t = clone(tree);
      walkTree(t, (n) => { if (n.type === "folder") n.open = false; });
      return t;
    }
    case "insert":
      return insertChild(tree, a.folderId, a.node);
    case "rename":
      return updateNode(tree, a.id, (n) => {
        if (n.type === "folder") n.name = a.value; else (n as NoteNode).title = a.value;
      });
    case "remove":
      return removeNode(tree, a.id);
    case "updateNote":
      return updateNode(tree, a.id, (n) => {
        Object.assign(n, a.patch);
        (n as NoteNode).updated = new Date().toISOString().slice(0, 10);
      });
    case "replace":
      return clone(a.tree);
    default:
      return tree;
  }
}
