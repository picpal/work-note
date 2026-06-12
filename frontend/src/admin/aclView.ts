/* aclView — 권한 관리 스크린의 표시용 순수 계산. 유효 권한 enforce는 서버 책임. */
import type { ApiAclRow, ApiPublicFlag } from "./api";

interface TreeNode { id: string; type: string; name?: string; title?: string; children?: TreeNode[]; }

/** parent 맵 구축 후 가까운 조상부터 반환. */
export function ancestorsOf(nodeId: string, tree: TreeNode[]): string[] {
  const parent = new Map<string, string | null>();
  const walk = (nodes: TreeNode[], p: string | null) => {
    for (const n of nodes) {
      parent.set(n.id, p);
      if (n.children) walk(n.children, n.id);
    }
  };
  walk(tree, null);
  const out: string[] = [];
  let cur = parent.get(nodeId) ?? null;
  while (cur) {
    out.push(cur);
    cur = parent.get(cur) ?? null;
  }
  return out;
}

export interface InheritedEntry extends ApiAclRow { fromNodeId: string; }

/** 조상 노드들의 ACL 엔트리 — 가까운 조상 순. 표시 전용(유효 권한 계산은 서버 책임). */
export function inheritedEntries(nodeId: string, tree: TreeNode[], all: ApiAclRow[]): InheritedEntry[] {
  const byNode = new Map<string, ApiAclRow[]>();
  for (const r of all) {
    const list = byNode.get(r.nodeId) ?? [];
    list.push(r);
    byNode.set(r.nodeId, list);
  }
  const out: InheritedEntry[] = [];
  for (const anc of ancestorsOf(nodeId, tree)) {
    for (const r of byNode.get(anc) ?? []) out.push({ ...r, fromNodeId: anc });
  }
  return out;
}

export function directPublicMode(nodeId: string, flags: ApiPublicFlag[]): "public" | "exclude" | null {
  return flags.find((f) => f.nodeId === nodeId)?.mode ?? null;
}

/** nearest-flag: 자기 → 조상 순으로 첫 플래그. 서버 AclResolver.publicRead와 동일 의미론 — 표시용. */
export function effectivePublic(nodeId: string, tree: TreeNode[], flags: ApiPublicFlag[]): boolean {
  const direct = directPublicMode(nodeId, flags);
  if (direct) return direct === "public";
  for (const anc of ancestorsOf(nodeId, tree)) {
    const m = directPublicMode(anc, flags);
    if (m) return m === "public";
  }
  return false;
}
