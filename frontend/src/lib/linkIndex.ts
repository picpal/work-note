import { walkTree } from "./tree";
import { parseLinks } from "./wikilink";
import type { VaultTree } from "../types";

export interface Backlink {
  sourceId: string;
  sourceTitle: string;
}
export type BacklinkIndex = Map<string, Backlink[]>;

// 대상 id → 그 대상을 가리키는 출발 노트 목록. self 제외, (source,target) 중복 제거.
export function buildBacklinks(tree: VaultTree): BacklinkIndex {
  const idx: BacklinkIndex = new Map();
  walkTree(tree, (n) => {
    if (n.type !== "note") return;
    const seen = new Set<string>(); // 이 노트가 이미 집계한 대상(중복 링크 1회만)
    for (const link of parseLinks(n.content)) {
      if (link.id === n.id || seen.has(link.id)) continue;
      seen.add(link.id);
      const list = idx.get(link.id) || [];
      list.push({ sourceId: n.id, sourceTitle: n.title || "제목 없음" });
      idx.set(link.id, list);
    }
  });
  return idx;
}
