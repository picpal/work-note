/* moveTargets — 이동 모달의 목적지 목록(루트 + 폴더) 구성·검색 필터 순수 로직.
   D-6: 목적지 목록이 폴더만 만들어 "루트(최상위)"로 되돌릴 길이 없던 결함을 메운다.
   루트의 실제 parentId는 null(인접 리스트 규약) — key는 리스트 렌더 전용 상수. */
import { folderOptions } from "../lib/tree";
import type { VaultTree } from "../types";

/** 리스트 key 전용. 서버로 나가는 값이 아니다(루트의 parentId는 항상 null). */
export const ROOT_KEY = "__ROOT__";
export const ROOT_LABEL = "루트 (최상위)";

export interface MoveTarget {
  key: string;
  /** 이동 API에 넘길 부모 id. 루트는 null. */
  parentId: string | null;
  label: string;
  kind: "root" | "space" | "folder";
}

/** 루트를 맨 위에 둔 목적지 목록. 폴더 후보는 기존 folderOptions(자신·자손 제외)를 그대로 쓴다. */
export function moveTargets(tree: VaultTree, excludeId: string): MoveTarget[] {
  const out: MoveTarget[] = [{ key: ROOT_KEY, parentId: null, label: ROOT_LABEL, kind: "root" }];
  for (const o of folderOptions(tree, excludeId)) {
    out.push({ key: o.id, parentId: o.id, label: o.label, kind: o.isRoot ? "space" : "folder" });
  }
  return out;
}

// 루트는 라벨 외 별칭으로도 찾히게 — "루트"/"최상위"/"root" 어느 쪽을 쳐도 나온다.
// "/"는 폴더 경로 라벨("A / B")의 구분자와 겹쳐 별칭에서 제외한다.
const ROOT_ALIASES = ["루트", "최상위", "root"];

export function filterMoveTargets(list: MoveTarget[], query: string): MoveTarget[] {
  const q = (query || "").trim().toLowerCase();
  if (!q) return list;
  return list.filter((t) => {
    if (t.label.toLowerCase().includes(q)) return true;
    return t.kind === "root" && ROOT_ALIASES.some((a) => a.includes(q));
  });
}

/** 현재 부모와 같은 목적지인가 — disabled + "현재 위치" 배지 판정(루트 포함). */
export function isCurrentTarget(t: MoveTarget, currentParentId: string | null): boolean {
  return t.parentId === currentParentId;
}
