import { findNode, isSelfOrDescendant } from "./tree";
import type { VaultTree } from "../types";

/** DnD 드롭 허용 판정. targetId=null은 루트(최상위) 드롭 — 트리 빈 영역.
 *  최상위(depth-0) 폴더는 이동 불가(스페이스 = 1급 메타데이터).
 *  허용 조건: dragged 존재 · 최상위 폴더 아님 · 현재 부모와 다름 ·
 *  (폴더 타깃일 때) 타깃이 폴더이고 자기/자손이 아님. */
export function canDropOn(tree: VaultTree, draggedId: string, targetId: string | null): boolean {
  if (draggedId === targetId) return false;
  const dragged = findNode(tree, draggedId);
  if (!dragged.node) return false;
  if (dragged.node.type === "folder" && dragged.parentNode === null) return false; // 최상위 폴더 immovable
  const currentParentId = dragged.parentNode?.id ?? null;
  if (currentParentId === targetId) return false;                                  // 무변경
  if (targetId === null) return true;                                              // 루트 복귀(D-6) — 이미 루트면 위에서 걸림
  const target = findNode(tree, targetId);
  if (!target.node || target.node.type !== "folder") return false;
  if (isSelfOrDescendant(tree, draggedId, targetId)) return false;
  return true;
}
