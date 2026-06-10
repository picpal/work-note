/* Admin screen 4: Permission management */
import React from "react";
import { ADMIN_USERS, ADMIN_TREE, ADMIN_GRANTS, ADMIN_PUBLIC, AdminTreeNode, Grant } from "../data";
import { SecHead, Avatar, Switch, RoleBadge } from "../common";
import { walkAdminTree } from "../tree";
import { Icon } from "../../components/Icon";

const { useState, useMemo } = React;
const h = React.createElement;

function PermNode({ node, depth, grants, onToggle, pub, onPub }: {
  node: AdminTreeNode;
  depth: number;
  grants: Record<string, Grant>;
  onToggle: (nodeId: string, kind: string) => void;
  pub: Record<string, boolean>;
  onPub: (nodeId: string) => void;
}): React.ReactElement {
  const [open, setOpen] = useState(true);
  const isFolder = node.type === "folder";
  const g = grants[node.id] || {};
  // inheritance: walk up — simplified, parent passed via grants merge done in parent
  const inheritedRead = g.inheritedRead;
  const read = g.read || inheritedRead;
  const edit = g.edit || g.inheritedEdit;
  const overridden = g.override;
  const isPublic = pub[node.id];

  return h(React.Fragment, null,
    h("div", { className: "ptree-row", style: { paddingLeft: 8 + depth * 18 } },
      isFolder
        ? h("span", { className: "tw" + (open ? " open" : ""), onClick: () => setOpen(!open) }, h(Icon, { name: "chevron" }))
        : h("span", { className: "tw" }),
      h("span", { className: "ic" }, h(Icon, { name: isFolder ? (open ? "folderOpen" : "folder") : "fileLines" })),
      h("span", { className: "nm" }, node.name),
      overridden && h("span", { className: "tagm override" }, "재정의됨"),
      (inheritedRead && !g.read) && h("span", { className: "tagm inherit" }, "상속"),
      isPublic && h("span", { className: "tagm" }, "공개"),
      h("span", { className: "perm-toggles" },
        h("button", {
          className: "ptoggle" + (read ? " on" : "") + (inheritedRead && !g.read ? " inherited" : ""),
          onClick: () => onToggle(node.id, "read"),
        }, "읽기"),
        h("button", {
          className: "ptoggle" + (edit ? " on" : "") + (g.inheritedEdit && !g.edit ? " inherited" : ""),
          onClick: () => onToggle(node.id, "edit"),
        }, "편집"))),
    isFolder && open && node.children && node.children.map((c) =>
      h(PermNode, { key: c.id, node: c, depth: depth + 1, grants, onToggle, pub, onPub })));
}

function flattenForPublic(tree: AdminTreeNode[], out?: AdminTreeNode[]): AdminTreeNode[] { // eslint-disable-line
  out = out || [];
  tree.forEach((n) => { out!.push(n); });
  return out;
}

// resource-centric view
function ResourceView() {
  const [nodeId, setNodeId] = useState("n-pipe");
  const all: AdminTreeNode[] = []; walkAdminTree(ADMIN_TREE, (n) => all.push(n));
  const node = all.find((n) => n.id === nodeId);
  // who can access this resource (derived from grants)
  const accessors = ADMIN_USERS.filter((u) => {
    const g = (ADMIN_GRANTS[u.id] || {});
    return g[nodeId] || (nodeId === "n-approve" && g["f-arch"]) || (["n-codes"].includes(nodeId) && g["f-ops"]) || u.role === "관리자";
  });
  return h("div", { className: "cols-perm" },
    h("div", { className: "panel" },
      h("div", { className: "panel-head" }, h(Icon, { name: "folder" }), "리소스 선택"),
      h("div", { className: "panel-body", style: { padding: 8 } },
        h("div", { className: "ptree" },
          all.filter((n) => n.type === "note").map((n) => h("div", {
            key: n.id, className: "ptree-row", style: { cursor: "default", background: n.id === nodeId ? "var(--bg-active)" : "" },
            onClick: () => setNodeId(n.id),
          },
            h("span", { className: "ic" }, h(Icon, { name: "fileLines" })),
            h("span", { className: "nm" }, n.name)))))),
    h("div", { className: "panel" },
      h("div", { className: "panel-head" }, h(Icon, { name: "users" }),
        h("span", null, h("b", { style: { color: "var(--ink)" } }, node ? node.name : ""), " 접근 가능 사용자")),
      h("div", { className: "panel-body", style: { padding: 0 } },
        h("table", { className: "atable" },
          h("thead", null, h("tr", null, h("th", null, "사번"), h("th", null, "역할"), h("th", { className: "right" }, "권한"))),
          h("tbody", null,
            accessors.map((u) => h("tr", { key: u.id },
              h("td", { className: "mono" }, u.emp),
              h("td", null, h(RoleBadge, { role: u.role })),
              h("td", { className: "right" }, h("span", { className: "badge role" }, u.role === "관리자" ? "전체" : ((ADMIN_GRANTS[u.id] || {})[nodeId]?.edit || (ADMIN_GRANTS[u.id] || {})["f-arch"]?.edit) ? "읽기+편집" : "읽기")))))))));
}

export function Permissions({ toast }: { toast: (msg: string, icon?: string) => void }) {
  const [view, setView] = useState("user");   // user | resource
  const [uid, setUid] = useState("u3");
  const [uq, setUq] = useState("");
  const [grants, setGrants] = useState(() => JSON.parse(JSON.stringify(ADMIN_GRANTS)) as Record<string, Record<string, Grant>>);
  const [pub, setPub] = useState(() => ({ ...ADMIN_PUBLIC }));
  const [dirty, setDirty] = useState(0);

  const users = ADMIN_USERS.filter((u) => u.role !== "관리자");
  const fUsers = users.filter((u) => !uq || u.emp.toLowerCase().includes(uq.toLowerCase()) || u.email.toLowerCase().includes(uq.toLowerCase()));
  const userGrants = grants[uid] || {};

  // compute inheritance for display
  const effective = useMemo(() => {
    const out: Record<string, Grant> = JSON.parse(JSON.stringify(userGrants));
    const walk = (nodes: AdminTreeNode[], parent: { read?: boolean; edit?: boolean } | null) => {
      nodes.forEach((n) => {
        const g: Grant = out[n.id] || (out[n.id] = {});
        if (parent) {
          if (parent.read && !g.read) g.inheritedRead = true;
          if (parent.edit && !g.edit) g.inheritedEdit = true;
        }
        if (n.children) walk(n.children, { read: g.read || g.inheritedRead, edit: g.edit || g.inheritedEdit });
      });
    };
    walk(ADMIN_TREE, null);
    return out;
  }, [userGrants, dirty]);

  const toggle = (nodeId: string, kind: string) => {
    setGrants((gr) => {
      const next: Record<string, Record<string, Grant>> = JSON.parse(JSON.stringify(gr));
      const u = next[uid] || (next[uid] = {});
      const g: Grant = u[nodeId] || (u[nodeId] = {});
      (g as Record<string, boolean>)[kind] = !(g as Record<string, boolean>)[kind];
      if (kind === "edit" && g.edit) g.read = true;       // edit implies read
      if (kind === "read" && !g.read) g.edit = false;     // remove read removes edit
      // mark override if this is a note under a granted folder
      g.override = !!(g.read || g.edit);
      if (!g.read && !g.edit) delete u[nodeId];
      return next;
    });
    setDirty((d) => d + 1);
  };
  const togglePub = (nodeId: string) => { setPub((p) => ({ ...p, [nodeId]: !p[nodeId] })); setDirty((d) => d + 1); };
  const commit = () => { setDirty(0); toast("권한 변경 사항을 적용했습니다", "check"); };

  const selUser = ADMIN_USERS.find((u) => u.id === uid)!;

  return h("div", { className: "apage wide" },
    h(SecHead, {
      title: "권한 관리",
      hint: "기본 거부 — 명시적으로 부여된 리소스만 접근 가능",
      right: h("div", { className: "seg" },
        h("button", { className: view === "user" ? "active" : "", onClick: () => setView("user") }, "사용자 중심"),
        h("button", { className: view === "resource" ? "active" : "", onClick: () => setView("resource") }, "리소스 중심")),
    }),
    view === "user"
      ? h("div", { className: "cols-perm" },
          // user picker
          h("div", null,
            h("div", { className: "afield", style: { marginBottom: 10, minWidth: 0 } },
              h(Icon, { name: "search" }), h("input", { placeholder: "사용자 검색", value: uq, onChange: (e: React.ChangeEvent<HTMLInputElement>) => setUq(e.target.value) })),
            h("div", { className: "upick" },
              fUsers.map((u) => h("div", { className: "upick-item" + (u.id === uid ? " active" : ""), key: u.id, onClick: () => setUid(u.id) },
                h(Avatar, { emp: u.emp }),
                h("div", { className: "info" },
                  h("div", { className: "emp mono" }, u.emp),
                  h("div", { className: "mail" }, u.email)),
                h("span", { className: "badge role" + (u.role === "관리자" ? " radmin" : "") }, u.role))))),
          // tree
          h("div", { className: "panel" },
            h("div", { className: "panel-head" },
              h(Avatar, { emp: selUser.emp, cls: "avatar" }),
              h("span", null, h("span", { className: "mono", style: { color: "var(--ink)" } }, selUser.emp), " 의 리소스 권한"),
              h("span", { style: { marginLeft: "auto", fontSize: 12, color: "var(--text-3)", fontWeight: 400 } }, "폴더 권한은 하위로 상속됩니다")),
            h("div", { className: "panel-body" },
              h("div", { className: "ptree" },
                ADMIN_TREE.map((n) => h(PermNode, { key: n.id, node: n, depth: 0, grants: effective, onToggle: toggle, pub, onPub: togglePub }))),
              h("div", { style: { marginTop: 14, paddingTop: 14, borderTop: "1px solid var(--border-soft)" } },
                h("div", { style: { fontSize: 12, fontWeight: 600, color: "var(--text-2)", marginBottom: 10 } }, "공개(Public) 설정 — 공개 시 방문자 포함 모두 열람"),
                h("div", { style: { display: "flex", flexDirection: "column", gap: 4 } },
                  flattenForPublic(ADMIN_TREE).map((n) => h("div", { key: n.id, style: { display: "flex", alignItems: "center", gap: 9, padding: "5px 8px", borderRadius: 6 } },
                    h("span", { style: { color: "var(--text-3)", display: "grid", placeItems: "center" } }, h(Icon, { name: n.type === "folder" ? "folder" : "fileLines" })),
                    h("span", { style: { flex: 1, fontSize: 13, color: "var(--text)" } }, n.name),
                    h(Switch, { on: !!pub[n.id], onChange: () => togglePub(n.id) }))))),
              dirty > 0 && h("div", { className: "changebar" },
                h(Icon, { name: "alert" }),
                h("span", { className: "txt" }, h("b", null, dirty + "건"), "의 변경 사항이 적용 대기 중입니다"),
                h("span", { className: "spacer" }),
                h("button", { className: "btn", onClick: () => { setGrants(JSON.parse(JSON.stringify(ADMIN_GRANTS))); setPub({ ...ADMIN_PUBLIC }); setDirty(0); } }, "되돌리기"),
                h("button", { className: "btn primary", onClick: commit }, "변경 적용")))))
      : h(ResourceView, null)
  );
}
