/* Admin screen 6: 팀·스페이스 — 팀(컨텍스트 teams + reload) / 스페이스(자체 로드: spaces + tree) */
import React from "react";
import { AdminApi, ApiSpace, ApiTeam, ApiUserBase } from "../api";
import { VaultApi } from "../../storage/VaultApi";
import type { FolderNode, VaultTree } from "../../types";
import { ApiError } from "../../api/http";
import { useAdminData } from "../useAdminData";
import { SecHead, Empty, Modal, SkeletonTable } from "../common";
import { Icon } from "../../components/Icon";
import { searchMembers, MEMBER_RESULT_LIMIT } from "../memberSearch";

const { useState, useEffect, useMemo, useCallback } = React;
const h = React.createElement;

type ModalState =
  | { kind: "createTeam" }
  | { kind: "renameTeam"; team: ApiTeam }
  | { kind: "deleteTeam"; team: ApiTeam }
  | { kind: "removeMember"; team: ApiTeam; user: ApiUserBase }
  | { kind: "unsetSpace"; nodeId: string }
  | null;

export function Teams({ toast }: { toast: (msg: string, icon?: string) => void }) {
  const { users, teams, reload } = useAdminData();
  const [spaces, setSpaces] = useState<ApiSpace[] | null>(null);
  const [tree, setTree] = useState<VaultTree | null>(null);
  const [selId, setSelId] = useState<string | null>(null);   // 멤버 패널 대상 팀
  const [modal, setModal] = useState<ModalState>(null);
  const [busy, setBusy] = useState(false);
  const [tname, setTname] = useState("");                    // 팀 생성/이름 변경 입력
  const [addUid, setAddUid] = useState("");                  // 멤버 추가 — 선택된 사용자 id
  const [addQuery, setAddQuery] = useState("");              // 멤버 추가 — 이름/사번 검색어
  const [addOpen, setAddOpen] = useState(false);             // 멤버 추가 — 검색 드롭다운 열림
  const [spNode, setSpNode] = useState("");                  // 스페이스 지정 — 폴더
  const [spOwner, setSpOwner] = useState("");                // 스페이스 지정 — 소유("" = 공용)

  // ---- 스페이스 데이터 자체 로드 (mount 시 tree 포함, 변이 후 spaces만) ----
  const loadSpaces = useCallback(async (withTree: boolean) => {
    try {
      if (withTree) {
        const [t, s] = await Promise.all([VaultApi.tree(), AdminApi.spaces()]);
        setTree(t); setSpaces(s);
      } else {
        setSpaces(await AdminApi.spaces());
      }
    } catch (e) {
      toast(e instanceof ApiError ? e.message : "스페이스 데이터를 불러오지 못했습니다");
    }
  }, [toast]);
  useEffect(() => { void loadSpaces(true); }, [loadSpaces]);

  /** Users.tsx run() 패턴 — 성공 시 갱신(after)+toast 후 true, 실패 시 서버 메시지(409/422 포함) 토스트 후 false(모달 유지). */
  const run = async (fn: () => Promise<unknown>, okMsg: string, icon?: string, after?: () => Promise<unknown>): Promise<boolean> => {
    if (busy) return false;
    setBusy(true);
    try {
      await fn();
      await (after ? after() : reload());
      toast(okMsg, icon);
      return true;
    } catch (e) {
      toast(e instanceof ApiError ? e.message : "요청 실패");
      return false;
    } finally {
      setBusy(false);
    }
  };
  const afterSpace = () => loadSpaces(false);

  // ---- 파생 ----
  const sel = useMemo(() => teams.find((t) => t.id === selId) ?? null, [teams, selId]);
  const spaceCount = (teamId: string) => spaces?.filter((s) => s.teamId === teamId).length;
  const rootFolders = useMemo(
    () => (tree ?? []).filter((n): n is FolderNode => n.type === "folder"), [tree]);
  const folderName = (nodeId: string) => rootFolders.find((f) => f.id === nodeId)?.name ?? nodeId;
  const teamName = (teamId: string) => teams.find((t) => t.id === teamId)?.name ?? teamId;
  const candidates = useMemo(() => {
    const taken = new Set((spaces ?? []).map((s) => s.nodeId));
    return rootFolders.filter((f) => !taken.has(f.id));
  }, [rootFolders, spaces]);
  const addables = useMemo(
    () => (sel ? users.filter((u) => u.status === "active" && !sel.members.some((m) => m.id === u.id)) : []),
    [users, sel]);
  const memberResults = useMemo(() => searchMembers(addables, addQuery), [addables, addQuery]);
  const addSel = useMemo(() => addables.find((u) => u.id === addUid) ?? null, [addables, addUid]);
  // 선택하면 칩으로 고정 — input은 언마운트돼 실수 편집을 차단한다.
  const pickMember = (u: ApiUserBase) => { setAddUid(u.id); setAddQuery(""); setAddOpen(false); };
  const clearMember = () => { setAddUid(""); setAddQuery(""); setAddOpen(false); };

  // ---- 팀 변이 ----
  const applyCreateTeam = async () => {
    const name = tname.trim();
    if (!name) { toast("팀 이름을 입력하세요"); return; }
    if (await run(() => AdminApi.createTeam(name), "팀 \"" + name + "\" 을(를) 생성했습니다", "users")) setModal(null);
  };
  const applyRenameTeam = async (t: ApiTeam) => {
    const name = tname.trim();
    if (!name) { toast("팀 이름을 입력하세요"); return; }
    if (await run(() => AdminApi.renameTeam(t.id, name), "팀 이름을 \"" + name + "\" (으)로 변경했습니다", "check")) setModal(null);
  };
  const applyDeleteTeam = async (t: ApiTeam) => {
    // 스페이스 소유 팀이면 서버 409 — run()이 서버 한국어 메시지를 토스트하고 모달은 유지된다.
    if (await run(() => AdminApi.deleteTeam(t.id), "팀 \"" + t.name + "\" 을(를) 삭제했습니다", "check")) {
      setModal(null);
      if (selId === t.id) setSelId(null);
    }
  };
  const applyAddMember = async (t: ApiTeam) => {
    if (!addUid) return;
    if (await run(() => AdminApi.addMember(t.id, addUid), "멤버를 추가했습니다", "userCheck")) {
      setAddUid(""); setAddQuery(""); setAddOpen(false);
    }
  };
  const applyRemoveMember = async (t: ApiTeam, u: ApiUserBase) => {
    if (await run(() => AdminApi.removeMember(t.id, u.id), u.emp + " 을(를) 팀에서 제거했습니다", "check")) setModal(null);
  };

  // ---- 스페이스 변이 ----
  const applySetSpace = async () => {
    if (!spNode) return;
    // 최상위 활성 폴더가 아니면 서버 422 — run()이 서버 메시지를 토스트.
    if (await run(() => AdminApi.setSpace(spNode, spOwner || null),
      "\"" + folderName(spNode) + "\" 을(를) 스페이스로 지정했습니다", "folder", afterSpace)) {
      setSpNode(""); setSpOwner("");
    }
  };
  const changeOwner = (sp: ApiSpace, v: string) => {
    if (v === (sp.teamId ?? "")) return;
    void run(() => AdminApi.setSpace(sp.nodeId, v || null),
      "\"" + folderName(sp.nodeId) + "\" 소유를 " + (v ? teamName(v) : "공용") + "(으)로 변경했습니다", "check", afterSpace);
  };
  const applyUnsetSpace = async (nodeId: string) => {
    if (await run(() => AdminApi.unsetSpace(nodeId), "스페이스 지정을 해제했습니다", "check", afterSpace)) setModal(null);
  };

  const selectTeam = (id: string) => { setSelId(id); setAddUid(""); setAddQuery(""); setAddOpen(false); };

  const fld = (label: string, input: React.ReactNode) =>
    h("div", { style: { marginBottom: 10 } }, h("label", { className: "flabel" }, label), input);
  const hintLine = (text: string) =>
    h("div", { style: { fontSize: 12, color: "var(--text-3)", lineHeight: 1.6, marginTop: 8 } }, text);
  const ownerOptions = [
    h("option", { key: "", value: "" }, "공용"),
    ...teams.map((t) => h("option", { key: t.id, value: t.id }, t.name)),
  ];

  return h("div", { className: "apage wide" },
    // ==== 팀 섹션 ====
    h(SecHead, { title: "팀", hint: teams.length + "팀 — 팀은 그룹입니다(역할 아님)",
      right: h("button", { className: "btn sm primary", disabled: busy,
        onClick: () => { setTname(""); setModal({ kind: "createTeam" }); } }, h(Icon, { name: "plus" }), "팀 생성") }),
    h("div", { style: { display: "grid", gridTemplateColumns: sel ? "1fr 340px" : "1fr", gap: 16, alignItems: "start" } },
      teams.length === 0
        ? h(Empty, { icon: "users", title: "팀이 없습니다", desc: "팀을 생성하고 멤버를 추가하세요." })
        : h("div", { className: "table-wrap" },
            h("table", { className: "atable" },
              h("thead", null, h("tr", null,
                h("th", null, "팀 이름"), h("th", null, "멤버"), h("th", null, "스페이스"), h("th", { className: "right" }, "작업"))),
              h("tbody", null,
                teams.map((t) => h("tr", { key: t.id },
                  h("td", null, h("b", { style: { color: "var(--ink)", fontWeight: 600 } }, t.name)),
                  h("td", { className: "mono" }, t.members.length + "명"),
                  h("td", { className: "mono" }, spaceCount(t.id) === undefined ? "—" : spaceCount(t.id) + "개"),
                  h("td", { className: "right" },
                    h("div", { className: "actions" },
                      h("button", { className: "lact", onClick: () => selectTeam(t.id) }, "멤버 관리"),
                      h("button", { className: "lact", disabled: busy,
                        onClick: () => { setTname(t.name); setModal({ kind: "renameTeam", team: t }); } }, "이름 변경"),
                      h("button", { className: "lact danger", disabled: busy,
                        onClick: () => setModal({ kind: "deleteTeam", team: t }) }, "삭제")))))))),
      // ---- 우: 선택 팀 멤버 패널 ----
      sel && h("div", { className: "panel" },
        h("div", { className: "panel-head" }, h(Icon, { name: "users" }),
          h("span", null, h("b", { style: { color: "var(--ink)" } }, sel.name), " 멤버"),
          h("button", { className: "lact", style: { marginLeft: "auto" }, onClick: () => setSelId(null) }, h(Icon, { name: "x" }))),
        h("div", { className: "panel-body" },
          sel.members.length === 0
            ? h("div", { style: { fontSize: 12.5, color: "var(--text-3)", padding: "6px 0" } }, "멤버가 없습니다.")
            : h("table", { className: "atable" },
                h("thead", null, h("tr", null,
                  h("th", null, "사번"), h("th", null, "이름"), h("th", { className: "right" }, ""))),
                h("tbody", null,
                  sel.members.map((m) => h("tr", { key: m.id },
                    h("td", { className: "mono" }, m.emp),
                    h("td", null, m.name),
                    h("td", { className: "right" },
                      h("button", { className: "lact danger", disabled: busy,
                        onClick: () => setModal({ kind: "removeMember", team: sel, user: m }) }, "제거")))))),
          h("div", { className: "btn-row", style: { marginTop: 12 } },
            h("div", { className: "member-search" },
              // 선택 후엔 칩으로 고정 — input을 언마운트해 실수 편집(→ 선택 해제) 자체를 차단.
              addSel
                ? h("div", { className: "member-chip" },
                    h("span", { className: "name" }, addSel.name),
                    h("span", { className: "emp mono" }, addSel.emp),
                    h("button", { className: "member-chip-x", type: "button", title: "선택 해제",
                      disabled: busy, onClick: clearMember }, h(Icon, { name: "x" })))
                : h(React.Fragment, null,
                    h("input", {
                      className: "member-search-input", type: "text", value: addQuery, autoFocus: true,
                      disabled: busy || addables.length === 0,
                      placeholder: addables.length === 0 ? "추가할 수 있는 사용자가 없습니다" : "이름 또는 사번으로 검색…",
                      onFocus: () => setAddOpen(true),
                      onBlur: () => setAddOpen(false),
                      onChange: (e: React.ChangeEvent<HTMLInputElement>) => { setAddQuery(e.target.value); setAddOpen(true); },
                    }),
                    // 결과는 onMouseDown(preventDefault)로 선택 — input blur가 클릭을 가로채지 못하게.
                    addOpen && addables.length > 0 && h("ul", { className: "member-search-list" },
                      memberResults.matches.length === 0
                        ? h("li", { className: "member-search-empty" }, "검색 결과가 없습니다")
                        : memberResults.shown.map((u) => h("li", {
                            key: u.id, className: "member-search-item",
                            onMouseDown: (e: React.MouseEvent) => { e.preventDefault(); pickMember(u); },
                          }, h("span", { className: "name" }, u.name), h("span", { className: "emp mono" }, u.emp))),
                      memberResults.matches.length > 0 && memberResults.truncated &&
                        h("li", { key: "__more", className: "member-search-empty" },
                          "처음 " + MEMBER_RESULT_LIMIT + "명만 표시 — 검색어를 더 좁혀보세요")))),
            h("button", { className: "btn sm", disabled: busy || !addUid, onClick: () => void applyAddMember(sel) },
              h(Icon, { name: "plus" }), "추가")),
          hintLine("멤버 제거 즉시 해당 사용자는 이 팀 경유 권한을 잃습니다.")))),
    // ==== 스페이스 섹션 ====
    h("div", { style: { marginTop: 28 } },
      h(SecHead, { title: "스페이스", hint: "최상위 팀 폴더 — 1급 메타데이터" }),
      spaces === null || tree === null
        ? h(SkeletonTable, { cols: 3, rows: 3 })
        : h("div", { className: "table-wrap" },
            h("table", { className: "atable" },
              h("thead", null, h("tr", null,
                h("th", null, "폴더"), h("th", null, "소유"), h("th", { className: "right" }, "작업"))),
              h("tbody", null,
                spaces.length === 0
                  ? h("tr", null, h("td", { colSpan: 3, style: { color: "var(--text-3)", fontSize: 12.5 } }, "지정된 스페이스가 없습니다."))
                  : spaces.map((sp) => h("tr", { key: sp.nodeId },
                      h("td", null, h(Icon, { name: "folder" }), " ", h("b", { style: { color: "var(--ink)", fontWeight: 600 } }, folderName(sp.nodeId))),
                      h("td", null, h("select", { className: "aselect", value: sp.teamId ?? "", disabled: busy,
                        onChange: (e: React.ChangeEvent<HTMLSelectElement>) => changeOwner(sp, e.target.value) },
                        ownerOptions)),
                      h("td", { className: "right" },
                        h("button", { className: "lact danger", disabled: busy,
                          onClick: () => setModal({ kind: "unsetSpace", nodeId: sp.nodeId }) }, "해제"))))))),
      // ---- 지정 ----
      h("div", { className: "btn-row", style: { marginTop: 12 } },
        h("select", { className: "aselect", value: spNode, disabled: busy || candidates.length === 0,
          onChange: (e: React.ChangeEvent<HTMLSelectElement>) => setSpNode(e.target.value) },
          h("option", { value: "" }, candidates.length === 0 ? "지정할 수 있는 최상위 폴더가 없습니다" : "최상위 폴더 선택…"),
          candidates.map((f) => h("option", { key: f.id, value: f.id }, f.name))),
        h("select", { className: "aselect", value: spOwner, disabled: busy,
          onChange: (e: React.ChangeEvent<HTMLSelectElement>) => setSpOwner(e.target.value) },
          ownerOptions),
        h("button", { className: "btn sm primary", disabled: busy || !spNode, onClick: () => void applySetSpace() },
          h(Icon, { name: "plus" }), "스페이스 지정")),
      hintLine("팀 소유로 지정하면 소유 팀에 edit 권한이 자동 부여됩니다. 최상위 활성 폴더만 지정할 수 있습니다.")),
    // ==== 모달 ====
    modal?.kind === "createTeam" && h(Modal, {
      icon: "users", title: "팀 생성", confirmLabel: "생성",
      onConfirm: () => void applyCreateTeam(), onClose: () => setModal(null),
    },
      fld("팀 이름", h("input", { className: "tinput", value: tname, autoFocus: true, placeholder: "예: 결제팀",
        onChange: (e: React.ChangeEvent<HTMLInputElement>) => setTname(e.target.value) }))),
    modal?.kind === "renameTeam" && h(Modal, {
      icon: "edit", title: "팀 이름 변경", confirmLabel: "변경",
      onConfirm: () => void applyRenameTeam(modal.team), onClose: () => setModal(null),
    },
      h("div", { style: { marginBottom: 10 } },
        h("b", { style: { color: "var(--ink)" } }, modal.team.name), " 팀의 이름을 변경합니다. 이름은 라벨일 뿐 권한에는 영향이 없습니다."),
      fld("새 이름", h("input", { className: "tinput", value: tname, autoFocus: true,
        onChange: (e: React.ChangeEvent<HTMLInputElement>) => setTname(e.target.value) }))),
    modal?.kind === "deleteTeam" && h(Modal, {
      icon: "trash", iconWarn: true, title: "팀 삭제", confirmLabel: "삭제", confirmDanger: true,
      onConfirm: () => void applyDeleteTeam(modal.team), onClose: () => setModal(null),
    }, h("span", null, h("b", { style: { color: "var(--ink)" } }, modal.team.name),
      " 팀을 삭제합니다. 멤버들은 이 팀 경유 권한을 모두 잃습니다. 스페이스를 소유한 팀은 삭제할 수 없습니다. 계속할까요?")),
    modal?.kind === "removeMember" && h(Modal, {
      icon: "users", iconWarn: true, title: "멤버 제거", confirmLabel: "제거", confirmDanger: true,
      onConfirm: () => void applyRemoveMember(modal.team, modal.user), onClose: () => setModal(null),
    }, h("span", null, h("b", { className: "mono", style: { color: "var(--ink)" } }, modal.user.emp),
      " (" + modal.user.name + ") 을(를) ", h("b", { style: { color: "var(--ink)" } }, modal.team.name),
      " 팀에서 제거합니다. 제거 즉시 이 팀 경유 권한이 사라집니다. 계속할까요?")),
    modal?.kind === "unsetSpace" && h(Modal, {
      icon: "folder", iconWarn: true, title: "스페이스 해제", confirmLabel: "해제", confirmDanger: true,
      onConfirm: () => void applyUnsetSpace(modal.nodeId), onClose: () => setModal(null),
    }, h("span", null, h("b", { style: { color: "var(--ink)" } }, folderName(modal.nodeId)),
      " 폴더의 스페이스 지정을 해제합니다. 폴더와 노트는 그대로 유지됩니다. 계속할까요?"))
  );
}
