/* aclView — 권한 관리 스크린의 표시용 순수 계산. 유효 권한 enforce는 서버 책임. */
import type { ApiAclEntry, ApiAclRow, ApiPublicFlag } from "./api";

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

/** 조상 deny에 막힌 이유. same = 같은 주체의 deny(deny-sticky), all = @all deny(다중 주체 deny-우선 합집합). */
export type DenyBlockKind = "same" | "all";

export interface DenyBlock {
  /** 막힌 draft 행의 인덱스. */
  index: number;
  kind: DenyBlockKind;
  /** deny가 걸려 있는 조상 노드 id — 화면에서 "어디서 막혔는지" 짚어주기 위함. */
  fromNodeId: string;
}

const pkey = (e: { principalType: string; principalId: string }) => e.principalType + ":" + e.principalId;

/**
 * 조상 deny 때문에 지금은 효력이 없는 allow 행들 (스펙 §5.1).
 *
 * - same: 같은 주체의 조상 deny — deny-sticky, "deny 아래 재허용 없음"이라 더 가까운 allow로 못 뒤집는다.
 * - all: 조상의 @all deny — 유효 권한 계산에 @all이 항상 주체로 끼므로(PermissionService.principals)
 *        deny-우선 합집합에서 어떤 주체의 allow든 함께 막힌다.
 *
 * 팀 deny가 그 팀 소속 사용자의 allow를 막는 경우는 여기서 판정하지 않는다 — 소속 정보가 있어야
 * 확실히 말할 수 있고, 근거 없는 "무효" 표시는 있는 것보다 나쁘다(서버는 어차피 막는다).
 *
 * 저장을 막는 용도가 아니다: 조상 deny는 나중에 걷힐 수 있고, 그때를 대비해 allow를 미리 걸어두는 것은
 * 정당한 운영이다. 결과가 지금은 무효라는 사실만 알린다.
 *
 * @param inherited 가까운 조상부터 정렬된 상속 엔트리(inheritedEntries 출력)
 */
export function ancestorDenyBlocks(
  draft: readonly ApiAclEntry[],
  inherited: readonly InheritedEntry[],
): DenyBlock[] {
  const nearestDeny = new Map<string, string>();   // principalKey → fromNodeId (가까운 조상 우선)
  for (const e of inherited) {
    if (e.grantType !== "deny") continue;
    const k = pkey(e);
    if (!nearestDeny.has(k)) nearestDeny.set(k, e.fromNodeId);
  }
  const allDenyFrom = nearestDeny.get("all:@all");

  const out: DenyBlock[] = [];
  draft.forEach((e, index) => {
    if (e.grantType === "deny") return;   // deny를 deny가 막는 건 모순이 아니다
    if (!e.principalId) return;           // 주체 미선택 행은 판정 보류
    const same = nearestDeny.get(pkey(e));
    if (same !== undefined) { out.push({ index, kind: "same", fromNodeId: same }); return; }
    if (allDenyFrom !== undefined) out.push({ index, kind: "all", fromNodeId: allDenyFrom });
  });
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
