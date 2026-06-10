/* Admin screen 3: User management */
import React from "react";
import { ADMIN_USERS, ADMIN_GRANTS, ADMIN_TREE, AdminUser } from "../data";
import { SecHead, Empty, Modal, StatusBadge, RoleBadge } from "../common";
import { walkAdminTree } from "../tree";
import { Icon } from "../../components/Icon";

const { useState, useMemo } = React;
const h = React.createElement;

export function Users({ toast }: { toast: (msg: string, icon?: string) => void }) {
  const [q, setQ] = useState("");
  const [role, setRole] = useState("all");
  const [status, setStatus] = useState("all");
  const [sel, setSel] = useState<AdminUser | null>(null);     // selected user for detail panel
  const [confirm, setConfirm] = useState<AdminUser | null>(null);
  const [users, setUsers] = useState(ADMIN_USERS.map((u) => ({ ...u })));

  const filtered = useMemo(() => users.filter((u) => {
    if (q && !(u.emp.toLowerCase().includes(q.toLowerCase()) || u.email.toLowerCase().includes(q.toLowerCase()))) return false;
    if (role !== "all" && u.role !== role) return false;
    if (status !== "all" && u.status !== status) return false;
    return true;
  }), [users, q, role, status]);

  const deactivate = () => {
    setUsers((us) => us.map((u) => u.id === confirm!.id ? { ...u, status: "비활성" } : u));
    toast(confirm!.emp + " 계정을 비활성화했습니다", "ban");
    setConfirm(null);
    setSel((s) => s && s.id === confirm!.id ? { ...s, status: "비활성" } : s);
  };
  const resetPw = (u: AdminUser) => toast(u.emp + " 비밀번호를 초기화했습니다", "refresh");
  const grants = (uid: string) => ADMIN_GRANTS[uid] || {};

  return h("div", { className: "apage wide" },
    h(SecHead, { title: "사용자 관리", hint: filtered.length + "명" }),
    h("div", { className: "atoolbar" },
      h("div", { className: "afield" }, h(Icon, { name: "search" }),
        h("input", { placeholder: "사번 또는 이메일 검색", value: q, onChange: (e: React.ChangeEvent<HTMLInputElement>) => setQ(e.target.value) })),
      h("select", { className: "aselect", value: role, onChange: (e: React.ChangeEvent<HTMLSelectElement>) => setRole(e.target.value) },
        h("option", { value: "all" }, "전체 역할"), h("option", null, "관리자"), h("option", null, "운영자"), h("option", null, "방문자")),
      h("select", { className: "aselect", value: status, onChange: (e: React.ChangeEvent<HTMLSelectElement>) => setStatus(e.target.value) },
        h("option", { value: "all" }, "전체 상태"), h("option", null, "활성"), h("option", null, "비활성"), h("option", null, "대기"))),
    h("div", { style: { display: "grid", gridTemplateColumns: sel ? "1fr 340px" : "1fr", gap: 16, alignItems: "start" } },
      filtered.length === 0
        ? h(Empty, { icon: "users", title: "조건에 맞는 사용자가 없습니다", desc: "검색어나 필터를 조정해 보세요." })
        : h("div", { className: "table-wrap" },
            h("table", { className: "atable" },
              h("thead", null, h("tr", null,
                h("th", null, "사번"), h("th", null, "이메일"), h("th", null, "역할"),
                h("th", null, "상태"), h("th", null, "마지막 로그인"), h("th", { className: "right" }, "작업"))),
              h("tbody", null,
                filtered.map((u) => h("tr", { key: u.id, style: { cursor: "default" } },
                  h("td", { className: "mono" }, u.emp),
                  h("td", null, u.email),
                  h("td", null, h(RoleBadge, { role: u.role })),
                  h("td", null, h(StatusBadge, { status: u.status })),
                  h("td", { className: "muted mono" }, u.last),
                  h("td", { className: "right" },
                    h("div", { className: "actions" },
                      h("button", { className: "lact", onClick: () => setSel(u) }, "권한 보기"),
                      h("button", { className: "lact", onClick: () => resetPw(u) }, "비번 초기화"),
                      u.status !== "비활성" && h("button", { className: "lact danger", onClick: () => setConfirm(u) }, "비활성화"))))))) ),
      sel && h("div", { className: "panel" },
        h("div", { className: "panel-head" }, h(Icon, { name: "user" }), "사용자 상세",
          h("button", { className: "lact", style: { marginLeft: "auto" }, onClick: () => setSel(null) }, h(Icon, { name: "x" }))),
        h("div", { className: "panel-body" },
          h("div", { className: "kv" },
            h("div", { className: "row" }, h("span", { className: "k" }, "사번"), h("span", { className: "v mono" }, sel.emp)),
            h("div", { className: "row" }, h("span", { className: "k" }, "이메일"), h("span", { className: "v" }, sel.email)),
            h("div", { className: "row" }, h("span", { className: "k" }, "역할"), h("span", { className: "v" }, h(RoleBadge, { role: sel.role }))),
            h("div", { className: "row" }, h("span", { className: "k" }, "상태"), h("span", { className: "v" }, h(StatusBadge, { status: sel.status }))),
            h("div", { className: "row" }, h("span", { className: "k" }, "마지막 로그인"), h("span", { className: "v mono muted" }, sel.last))),
          h("div", { style: { marginTop: 18, marginBottom: 8, fontSize: 12, fontWeight: 600, color: "var(--text-2)" } }, "부여된 리소스 권한"),
          (function () {
            const g = grants(sel.id);
            const keys = Object.keys(g);
            const nameOf = (id: string) => { let n = id; walkAdminTree(ADMIN_TREE, (x) => { if (x.id === id) n = x.name; }); return n; };
            if (keys.length === 0) return h("div", { style: { fontSize: 12.5, color: "var(--text-3)", lineHeight: 1.6 } }, "부여된 권한이 없습니다. 방문자는 공개 노트만 열람할 수 있습니다.");
            return h("div", { style: { display: "flex", flexDirection: "column", gap: 7 } },
              keys.map((k) => h("div", { key: k, style: { display: "flex", alignItems: "center", gap: 8, fontSize: 12.5 } },
                h("span", { style: { color: "var(--text-3)", display: "grid", placeItems: "center" } }, h(Icon, { name: k.startsWith("f-") ? "folder" : "fileLines" })),
                h("span", { style: { flex: 1, color: "var(--text)" } }, nameOf(k)),
                h("span", { className: "badge role" }, g[k].edit ? "읽기+편집" : "읽기"))));
          })(),
          h("div", { className: "btn-row", style: { marginTop: 18 } },
            h("button", { className: "btn sm" }, "역할 변경"),
            h("button", { className: "btn sm", onClick: () => resetPw(sel) }, "비밀번호 초기화"))))),
    confirm && h(Modal, {
      icon: "ban", iconWarn: true, title: "계정 비활성화", confirmLabel: "비활성화", confirmDanger: true,
      onConfirm: deactivate, onClose: () => setConfirm(null),
    }, h("span", null, h("b", { className: "mono", style: { color: "var(--ink)" } }, confirm.emp), " 계정을 비활성화합니다. 해당 사용자는 로그인할 수 없게 됩니다. 계속할까요?"))
  );
}
