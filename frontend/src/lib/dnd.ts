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
