import { findNode, isSelfOrDescendant } from "./tree";
import type { VaultTree } from "../types";

/** DnD 드롭 허용 판정. targetId=null 은 루트.
 *  허용 조건: dragged 존재 · (타깃이 폴더이거나 루트) · 자기/자손 폴더 아님 · 현재 부모와 다름. */
export function canDropOn(tree: VaultTree, draggedId: string, targetId: string | null): boolean {
  if (draggedId === targetId) return false;
  const dragged = findNode(tree, draggedId);
  if (!dragged.node) return false;
  if (targetId !== null) {
    const target = findNode(tree, targetId);
    if (!target.node || target.node.type !== "folder") return false;
    if (isSelfOrDescendant(tree, draggedId, targetId)) return false;
  }
  const currentParentId = dragged.parentNode?.id ?? null;
  if (currentParentId === targetId) return false;
  return true;
}
