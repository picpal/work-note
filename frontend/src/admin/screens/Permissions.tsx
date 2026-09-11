/* Admin screen 4: 권한 관리 — 노드 중심 ACL 편집·상속 표시·public 토글 (실 API 배선) */
import React from "react";
import { AdminApi, ApiAclEntry, ApiAclRow, ApiPublicFlag } from "../api";
import { VaultApi } from "../../storage/VaultApi";
import type { VaultNode, VaultTree } from "../../types";
import { ApiError } from "../../api/http";
import { useAdminData } from "../useAdminData";
import { ancestorDenyBlocks, directPublicMode, effectivePublic, inheritedEntries } from "../aclView";
import type { DenyBlock } from "../aclView";
import { SecHead, Empty, SkeletonTable } from "../common";
import { folderIconName } from "../../lib/tree";
import { Icon } from "../../components/Icon";
import { ConfirmDialog } from "../../components/ConfirmDialog";

const { useState, useEffect, useMemo, useCallback } = React;
const h = React.createElement;

const GRANTS: ReadonlyArray<readonly [ApiAclEntry["grantType"], string]> =
  [["read", "읽기"], ["edit", "편집"], ["deny", "거부"]];
const grantLabel = (g: string) => GRANTS.find(([k]) => k === g)?.[1] ?? g;
const nodeLabel = (n: VaultNode) => (n.type === "folder" ? n.name : n.title);

function flatten(tree: VaultTree, out: VaultNode[] = []): VaultNode[] {
  for (const n of tree) { out.push(n); if (n.type === "folder") flatten(n.children, out); }
  return out;
}

/** replace-all draft와 서버 상태의 더티 비교 — 순서 무관. */
const canon = (es: ApiAclEntry[]) =>
  JSON.stringify(es.map((e) => e.principalType + "|" + e.principalId + "|" + e.grantType).sort());

const principalKey = (e: ApiAclEntry) => e.principalType + ":" + e.principalId;

// ---- 좌측 트리 ----
function TreeRow({ node, depth, selId, pubIds, onSelect }: {
  node: VaultNode;
  depth: number;
  selId: string | null;
  pubIds: Set<string>;
  onSelect: (id: string) => void;
}): React.ReactElement {
  const [open, setOpen] = useState(true);
  const isFolder = node.type === "folder";
  return h(React.Fragment, null,
    h("div", {
      className: "ptree-row",
      style: { paddingLeft: 8 + depth * 18, cursor: "default", background: node.id === selId ? "var(--bg-active)" : "" },
      onClick: () => onSelect(node.id),
    },
      isFolder
        ? h("span", { className: "tw" + (open ? " open" : ""), onClick: (e: React.MouseEvent) => { e.stopPropagation(); setOpen(!open); } }, h(Icon, { name: "chevron" }))
        : h("span", { className: "tw" }),
      h("span", { className: "ic" }, h(Icon, { name: isFolder ? folderIconName(depth, open) : "fileLines" })),
      h("span", { className: "nm" }, nodeLabel(node)),
      pubIds.has(node.id) && h("span", { className: "tagm" }, "공개")),
    isFolder && open && node.children.map((c) =>
      h(TreeRow, { key: c.id, node: c, depth: depth + 1, selId, pubIds, onSelect })));
}

export function Permissions({ toast }: { toast: (msg: string, icon?: string) => void }) {
  const { users, teams } = useAdminData();
  const [tree, setTree] = useState<VaultTree | null>(null);
  const [acl, setAcl] = useState<ApiAclRow[]>([]);
  const [flags, setFlags] = useState<ApiPublicFlag[]>([]);
  const [selId, setSelId] = useState<string | null>(null);
  // 미저장 변경을 버리고 이동할지 — 네이티브 confirm 대신 앱 모달(P-1).
  const [pendingNav, setPendingNav] = useState<string | null>(null);
  const [draft, setDraft] = useState<ApiAclEntry[]>([]);
  const [busy, setBusy] = useState(false);

  const load = useCallback(async (withTree: boolean) => {
    try {
      if (withTree) {
        const [t, a, f] = await Promise.all([VaultApi.tree(), AdminApi.aclAll(), AdminApi.publicFlags()]);
        setTree(t); setAcl(a); setFlags(f);
      } else {
        const [a, f] = await Promise.all([AdminApi.aclAll(), AdminApi.publicFlags()]);
        setAcl(a); setFlags(f);
      }
    } catch (e) {
      toast(e instanceof ApiError ? e.message : "권한 데이터를 불러오지 못했습니다");
    }
  }, [toast]);
  useEffect(() => { void load(true); }, [load]);

  /** Users.tsx run() 패턴 — 변이 성공 시 acl/publicFlags 재로드 + toast. */
  const run = async (fn: () => Promise<unknown>, okMsg: string, icon?: string): Promise<boolean> => {
    if (busy) return false;
    setBusy(true);
    try {
      await fn();
      await load(false);
      toast(okMsg, icon);
      return true;
    } catch (e) {
      toast(e instanceof ApiError ? e.message : "요청 실패");
      return false;
    } finally {
      setBusy(false);
    }
  };

  const byId = useMemo(() => {
    const m = new Map<string, VaultNode>();
    for (const n of flatten(tree ?? [])) m.set(n.id, n);
    return m;
  }, [tree]);
  const sel = selId ? byId.get(selId) ?? null : null;

  const pubIds = useMemo(() => {
    if (!tree) return new Set<string>();
    return new Set(flatten(tree).filter((n) => effectivePublic(n.id, tree, flags)).map((n) => n.id));
  }, [tree, flags]);

  const serverEntries = useMemo(
    () => acl.filter((r) => r.nodeId === selId).map(({ principalType, principalId, grantType }) => ({ principalType, principalId, grantType })),
    [acl, selId]);
  const dirty = canon(draft) !== canon(serverEntries);
  const dupKeys = useMemo(() => {
    const seen = new Set<string>(); const dup = new Set<string>();
    for (const e of draft) { const k = principalKey(e); if (seen.has(k)) dup.add(k); seen.add(k); }
    return dup;
  }, [draft]);
  const incomplete = draft.some((e) => !e.principalId);
  const inherited = useMemo(
    () => (selId && tree ? inheritedEntries(selId, tree, acl) : []),
    [selId, tree, acl]);
  // deny-sticky(§5.1): 조상 deny에 막혀 지금은 효력이 없는 allow 행 — 저장은 되지만 결과가 없다.
  const denyBlocks = useMemo(() => ancestorDenyBlocks(draft, inherited), [draft, inherited]);
  const blockByRow = useMemo(() => new Map(denyBlocks.map((b) => [b.index, b])), [denyBlocks]);

  const applySelect = useCallback((id: string) => {
    setSelId(id);
    setDraft(acl.filter((r) => r.nodeId === id).map(({ principalType, principalId, grantType }) => ({ principalType, principalId, grantType })));
  }, [acl]);

  const select = (id: string) => {
    if (busy || id === selId) return;  // 저장 in-flight 중 선택 변경 금지 — draft 오염 경로 차단
    if (dirty) { setPendingNav(id); return; }  // 미저장 변경이 있으면 물어본다
    applySelect(id);
  };

  const principalLabel = (type: ApiAclEntry["principalType"], id: string): string => {
    if (type === "all") return "전체 사용자(@all)";
    if (type === "user") { const u = users.find((x) => x.id === id); return u ? u.emp + " (" + u.name + ")" : id; }
    const t = teams.find((x) => x.id === id);
    return t ? t.name : id;
  };

  const nodeName = (id: string) => { const n = byId.get(id); return n ? nodeLabel(n) : id; };

  /** 무효 표시의 근거 — 어디의 어떤 deny에 막혔는지. */
  const denyBlockHint = (b: DenyBlock): string =>
    b.kind === "same"
      ? `조상 "${nodeName(b.fromNodeId)}" 에 같은 주체의 거부가 있습니다. deny-sticky(스펙 §5.1) — 하위 allow로 뒤집을 수 없어 지금은 효력이 없습니다.`
      : `조상 "${nodeName(b.fromNodeId)}" 에 전체 사용자(@all) 거부가 있습니다. deny 절대 우선 — 어떤 주체의 허용도 함께 막힙니다.`;

  // ---- draft 편집 ----
  const patchRow = (i: number, patch: Partial<ApiAclEntry>) =>
    setDraft((d) => d.map((e, j) => (j === i ? { ...e, ...patch } : e)));
  const changeType = (i: number, type: ApiAclEntry["principalType"]) =>
    patchRow(i, { principalType: type, principalId: type === "all" ? "@all" : "" });
  const addRow = () => setDraft((d) => [...d, { principalType: "user", principalId: "", grantType: "read" }]);
  const removeRow = (i: number) => setDraft((d) => d.filter((_, j) => j !== i));

  const save = async () => {
    if (!sel) return;
    // 조상 deny에 막힌 행이 있어도 저장은 막지 않는다 — 조상 deny가 걷히면 살아나므로 미리 걸어두는 건
    // 정당한 운영이다. 다만 "저장했습니다"로 뭉개면 권한을 준 줄 알게 되니 결과를 토스트에 함께 말한다.
    const blocked = denyBlocks.length;
    const msg = blocked > 0
      ? `${nodeLabel(sel)} ACL을 저장했습니다 — ${blocked}건은 조상 deny에 막혀 지금은 효력이 없습니다`
      : nodeLabel(sel) + " ACL을 저장했습니다";
    // setDraft 불필요 — 성공 시 재로드된 acl에서 serverEntries가 다시 derive되어 dirty가 풀린다.
    // (draft를 여기서 덮어쓰면 in-flight 중 노드가 바뀌었을 때 이전 노드 entries로 오염될 수 있음)
    await run(() => AdminApi.setAcl(sel.id, [...draft]), msg, blocked > 0 ? "alert" : "check");
  };

  // ---- public 토글 ----
  const direct = sel ? directPublicMode(sel.id, flags) : null;
  const changePublic = (mode: string) => {
    if (!sel || mode === (direct ?? "")) return;
    void run(
      () => (mode === "" ? AdminApi.unsetPublic(sel.id) : AdminApi.setPublic(sel.id, mode as "public" | "exclude")),
      "공개 설정을 변경했습니다", "eye");
  };

  const secTitle = (text: string) =>
    h("div", { style: { fontSize: 12, fontWeight: 600, color: "var(--text-2)", marginBottom: 8 } }, text);
  const hintLine = (text: string) =>
    h("div", { style: { fontSize: 12, color: "var(--text-3)", lineHeight: 1.6, marginTop: 8 } }, text);

  const idOptions = (e: ApiAclEntry) => {
    if (e.principalType === "all") return [h("option", { key: "@all", value: "@all" }, "전체 사용자(@all)")];
    const opts = e.principalType === "user"
      ? users.map((u) => h("option", { key: u.id, value: u.id }, u.emp + " (" + u.name + ")"))
      : teams.map((t) => h("option", { key: t.id, value: t.id }, t.name));
    // 삭제된 주체 등 목록에 없는 id는 원문 그대로 보존
    if (e.principalId && !(e.principalType === "user" ? users.some((u) => u.id === e.principalId) : teams.some((t) => t.id === e.principalId)))
      opts.push(h("option", { key: e.principalId, value: e.principalId }, e.principalId));
    return [h("option", { key: "", value: "" }, "선택…"), ...opts];
  };

  return h("div", { className: "apage wide" },
    h(SecHead, {
      title: "권한 관리",
      hint: "유효 권한 = 역할 상한 ∩ ACL — 폴더 상속 + deny 절대 우선",
    }),
    h("div", { className: "cols-perm" },
      // ---- 좌: 트리 ----
      h("div", { className: "panel" },
        h("div", { className: "panel-head" }, h(Icon, { name: "folder" }), "노드 선택"),
        h("div", { className: "panel-body", style: { padding: 8 } },
          tree === null
            ? h(SkeletonTable, { cols: 1, rows: 6 })
            : tree.length === 0
              ? h(Empty, { icon: "folder", title: "노드가 없습니다" })
              : h("div", { className: "ptree" },
                  tree.map((n) => h(TreeRow, { key: n.id, node: n, depth: 0, selId, pubIds, onSelect: select }))))),
      // ---- 우: 선택 노드 ACL ----
      !sel
        ? h("div", { className: "panel" },
            h("div", { className: "panel-body" },
              h(Empty, { icon: "lock", title: "노드를 선택하세요", desc: "왼쪽 트리에서 폴더·노트를 선택하면 ACL을 편집할 수 있습니다." })))
        : h("div", { className: "panel" },
            h("div", { className: "panel-head" },
              h(Icon, { name: sel.type === "folder" ? "folder" : "fileLines" }),
              h("span", null, h("b", { style: { color: "var(--ink)" } }, nodeLabel(sel)), " 의 접근 제어"),
              pubIds.has(sel.id)
                ? h("span", { className: "badge active", style: { marginLeft: "auto" } }, h("span", { className: "bdot" }), "전체 공개 노출됨")
                : h("span", { className: "badge inactive", style: { marginLeft: "auto" } }, h("span", { className: "bdot" }), "전체 공개 아님")),
            h("div", { className: "panel-body" },
              // 1. 직접 ACL 편집
              secTitle("직접 ACL — 이 노드에 부여된 엔트리 (저장 시 전량 교체)"),
              draft.length === 0
                ? h("div", { style: { fontSize: 12.5, color: "var(--text-3)", padding: "6px 0" } }, "직접 엔트리가 없습니다 — 상속·역할 상한만 적용됩니다.")
                : h("table", { className: "atable" },
                    h("thead", null, h("tr", null,
                      h("th", null, "주체 유형"), h("th", null, "주체"), h("th", null, "권한"), h("th", { className: "right" }, ""))),
                    h("tbody", null,
                      draft.map((e, i) => h("tr", { key: i, style: dupKeys.has(principalKey(e)) || blockByRow.has(i) ? { background: "var(--bg-sunken)" } : undefined },
                        h("td", null, h("select", { className: "aselect", value: e.principalType,
                          onChange: (ev: React.ChangeEvent<HTMLSelectElement>) => changeType(i, ev.target.value as ApiAclEntry["principalType"]) },
                          h("option", { value: "user" }, "사용자"),
                          h("option", { value: "team" }, "팀"),
                          h("option", { value: "all" }, "전체"))),
                        h("td", null, h("select", { className: "aselect", value: e.principalId, disabled: e.principalType === "all",
                          onChange: (ev: React.ChangeEvent<HTMLSelectElement>) => patchRow(i, { principalId: ev.target.value }) },
                          idOptions(e))),
                        h("td", null, h("select", { className: "aselect", value: e.grantType,
                          onChange: (ev: React.ChangeEvent<HTMLSelectElement>) => patchRow(i, { grantType: ev.target.value as ApiAclEntry["grantType"] }) },
                          GRANTS.map(([k, label]) => h("option", { key: k, value: k }, label))),
                          // 조상 deny에 막힌 행 — "권한을 줬다"는 오독을 여기서 끊는다
                          blockByRow.has(i) && h("span", {
                            style: { marginLeft: 8, fontSize: 11, fontWeight: 600, color: "#b3261e", whiteSpace: "nowrap" },
                            title: denyBlockHint(blockByRow.get(i)!),
                          }, "조상 deny로 무효")),
                        h("td", { className: "right" },
                          h("button", { className: "lact danger", onClick: () => removeRow(i) }, "삭제")))))),
              // deny-sticky 경고 — 상시 안내문과 달리 "지금 이 편집이 무효"라는 신호다. 저장은 막지 않는다.
              denyBlocks.length > 0 && h("div", {
                className: "changebar",
                style: { position: "static", marginTop: 10, marginBottom: 0, alignItems: "flex-start" },
              },
                h(Icon, { name: "alert" }),
                h("span", { className: "txt" },
                  h("b", null, denyBlocks.length + "건"),
                  " 은 조상 deny에 막혀 저장해도 효력이 없습니다 — ",
                  denyBlocks.map((b, k) =>
                    h("span", { key: k },
                      k > 0 ? ", " : "",
                      h("b", null, principalLabel(draft[b.index].principalType, draft[b.index].principalId)),
                      ` ← ${nodeName(b.fromNodeId)}${b.kind === "all" ? " 의 @all 거부" : " 의 거부"}`)),
                  ". 조상 deny를 먼저 걷어내야 적용됩니다(deny-sticky, 스펙 §5.1). 나중에 deny가 풀릴 것을 대비해 미리 저장해두는 것은 괜찮습니다.")),

              h("div", { className: "btn-row", style: { marginTop: 10 } },
                h("button", { className: "btn sm", disabled: busy, onClick: addRow }, h(Icon, { name: "plus" }), "행 추가"),
                h("span", { style: { flex: 1 } }),
                dupKeys.size > 0 && h("span", { style: { fontSize: 12, color: "var(--text-3)" } }, "같은 주체가 중복되었습니다 — 행을 정리하세요"),
                !dupKeys.size && incomplete && h("span", { style: { fontSize: 12, color: "var(--text-3)" } }, "주체를 선택하세요"),
                dirty && !dupKeys.size && !incomplete && h("span", { style: { fontSize: 12, color: "var(--text-2)" } }, "저장되지 않은 변경"),
                h("button", { className: "btn sm primary", disabled: busy || !dirty || dupKeys.size > 0 || incomplete, onClick: () => void save() }, "저장")),
              // 2. 상속 엔트리
              h("div", { style: { marginTop: 18, paddingTop: 14, borderTop: "1px solid var(--border-soft)" } },
                secTitle("상속 엔트리 — 조상 폴더에서 내려옴 (읽기 전용, 가까운 조상 순)"),
                inherited.length === 0
                  ? h("div", { style: { fontSize: 12.5, color: "var(--text-3)", padding: "6px 0" } }, "상속되는 엔트리가 없습니다.")
                  : h("table", { className: "atable" },
                      h("thead", null, h("tr", null,
                        h("th", null, "출처"), h("th", null, "주체"), h("th", { className: "right" }, "권한"))),
                      h("tbody", null,
                        inherited.map((e, i) => {
                          // deny-sticky 오독 방지: 같은 주체의 allow가 위(더 가까운 조상)에 있어도 이 deny가 이긴다
                          const winsOverAbove = e.grantType === "deny" &&
                            inherited.slice(0, i).some((o) => principalKey(o) === principalKey(e) && o.grantType !== "deny");
                          return h("tr", { key: i },
                            h("td", null, h("span", { className: "tagm", style: { fontSize: 11, color: "var(--text-3)", border: "1px solid var(--border)", borderRadius: 5, padding: "1px 6px" } },
                              byId.get(e.fromNodeId) ? nodeLabel(byId.get(e.fromNodeId)!) : e.fromNodeId)),
                            h("td", null, principalLabel(e.principalType, e.principalId)),
                            h("td", { className: "right" },
                              winsOverAbove && h("span", { style: { fontSize: 11, color: "var(--text-2)", marginRight: 6 } }, "(우선 적용)"),
                              h("span", { className: "badge " + (e.grantType === "deny" ? "active" : "role") }, grantLabel(e.grantType))));
                        }))),
                hintLine("같은 주체의 조상 deny는 하위 allow로 뒤집을 수 없습니다 (deny-sticky).")),
              // 3. public 설정
              h("div", { style: { marginTop: 18, paddingTop: 14, borderTop: "1px solid var(--border-soft)" } },
                secTitle("공개(Public) 설정 — read 전용, nearest flag"),
                h("div", { style: { display: "flex", alignItems: "center", gap: 10 } },
                  h("select", { className: "aselect", value: direct ?? "", disabled: busy,
                    onChange: (ev: React.ChangeEvent<HTMLSelectElement>) => changePublic(ev.target.value) },
                    h("option", { value: "" }, "설정 없음(상속)"),
                    h("option", { value: "public" }, "공개(public)"),
                    sel.type === "note" && h("option", { value: "exclude" }, "공개 제외(exclude)")),
                  pubIds.has(sel.id)
                    ? h("span", { className: "badge active" }, h("span", { className: "bdot" }), "전체 공개 노출됨")
                    : h("span", { className: "badge inactive" }, h("span", { className: "bdot" }), "전체 공개 아님")),
                sel.type === "note"
                  ? hintLine("exclude는 공개 폴더 안에서 이 노트만 비공개로 빼는 카브아웃입니다.")
                  : hintLine("폴더 공개는 하위로 cascade되며, 하위 노트는 exclude로 개별 제외할 수 있습니다.")))))
    , pendingNav && h(ConfirmDialog, {
      key: "nav", name: "저장되지 않은 권한 변경", action: "다른 노드로 이동",
      message: ["변경 내용을 버리고 이동합니다.", "이동하면 이 노드에 적용하지 않은 ACL 편집이 사라집니다."],
      danger: true, confirmLabel: "버리고 이동", cancelLabel: "돌아가기",
      onConfirm: () => { const id = pendingNav; setPendingNav(null); applySelect(id); },
      onCancel: () => setPendingNav(null),
    }));
}
