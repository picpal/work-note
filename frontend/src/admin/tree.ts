import type { AdminTreeNode } from "./data";

export function walkAdminTree(tree: AdminTreeNode[], cb: (n: AdminTreeNode) => void): void {
  tree.forEach((n) => { cb(n); if (n.children) walkAdminTree(n.children, cb); });
}
