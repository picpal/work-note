/* Admin screens 1-3: Dashboard, Pending approvals, User management */
(function () {
  const { useState, useMemo } = React;
  const Icon = window.Icon;
  const h = React.createElement;

  // ============ 1. Dashboard ============
  function Dashboard({ go, toast }) {
    const users = window.ADMIN_USERS;
    const pending = window.ADMIN_PENDING;
    const total = users.length;
    const active = users.filter((u) => u.status === "활성").length;
    const inactive = users.filter((u) => u.status === "비활성").length;
    const stats = [
      { label: "전체 사용자", num: total, delta: "운영자 3 · 방문자 3 · 관리자 1" },
      { label: "승인 대기", num: pending.length, delta: "신규 가입 신청" },
      { label: "활성 계정", num: active, delta: "최근 7일 로그인 5명" },
      { label: "비활성 계정", num: inactive, delta: "관리자 비활성 처리" },
    ];
    const actIcon = { login: "logout", grant: "key", approve: "userCheck", reset: "refresh", revoke: "ban", deactivate: "ban", loginfail: "alert" };
    const recent = window.ADMIN_AUDIT.slice(0, 5);

    return h("div", { className: "apage" },
      h(window.SecHead, { title: "개요", hint: "워크스페이스 사용자 및 접근 현황" }),
      h("div", { className: "stat-grid" },
        stats.map((s, i) => h("div", { className: "stat", key: i },
          h("div", { className: "label" }, s.label),
          h("div", { className: "num" }, s.num),
          h("div", { className: "delta" }, s.delta)))),
      h("div", { className: "cols2", style: { marginTop: 26 } },
        h("div", { className: "panel" },
          h("div", { className: "panel-head" },
            h(Icon, { name: "userCheck" }), "승인 대기",
            h("span", { style: { marginLeft: "auto" } },
              h("button", { className: "btn sm", onClick: () => go("pending") }, "전체 보기"))),
          h("div", { className: "panel-body", style: { paddingTop: 4, paddingBottom: 4 } },
            pending.length === 0
              ? h(window.Empty, { icon: "userCheck", title: "대기 중인 신청 없음" })
              : h("div", { className: "mini-log" },
                  pending.map((p) => h("div", { className: "ml", key: p.id },
                    h("span", { className: "ml-ic" }, h(Icon, { name: "user" })),
                    h("span", { className: "ml-act mono" }, p.emp),
                    h("span", { className: "ml-tgt" }, p.email),
                    h("span", { className: "ml-at" }, p.at)))))),
        h("div", { className: "panel" },
          h("div", { className: "panel-head" },
            h(Icon, { name: "history" }), "최근 감사 로그",
            h("span", { style: { marginLeft: "auto" } },
              h("button", { className: "btn sm", onClick: () => go("audit") }, "전체 보기"))),
          h("div", { className: "panel-body", style: { paddingTop: 4, paddingBottom: 4 } },
            h("div", { className: "mini-log" },
              recent.map((r, i) => h("div", { className: "ml", key: i },
                h("span", { className: "ml-ic" }, h(Icon, { name: actIcon[r.actType] || "dot" })),
                h("span", { className: "ml-act" }, r.act),
                h("span", { className: "ml-tgt mono" }, r.who),
                h("span", { className: "ml-at" }, r.at.slice(5))))))))
    );
  }

  // ============ 2. Pending approvals ============
  function Pending({ toast }) {
    const [rows, setRows] = useState(window.ADMIN_PENDING.map((p) => ({ ...p })));
    const [confirm, setConfirm] = useState(null); // {type, row}
    const act = (row, type) => setConfirm({ row, type });
    const apply = () => {
      const { row, type } = confirm;
      setRows((rs) => rs.filter((r) => r.id !== row.id));
      toast(type === "approve" ? row.emp + " 승인됨 (방문자로 활성화)" : row.emp + " 반려됨", type === "approve" ? "userCheck" : "ban");
      setConfirm(null);
    };

    return h("div", { className: "apage" },
      h(window.SecHead, { title: "가입 승인 대기", hint: "폐쇄망 정책상 가입은 관리자 승인 후 활성화됩니다" }),
      h("div", { className: "panel", style: { marginBottom: 14, background: "var(--bg-sunken)" } },
        h("div", { className: "panel-body", style: { display: "flex", gap: 10, alignItems: "center", padding: "11px 16px" } },
          h("span", { style: { color: "var(--text-3)", display: "grid", placeItems: "center" } }, h(Icon, { name: "info" })),
          h("span", { style: { fontSize: 12.5, color: "var(--text-2)" } },
            "승인 시 계정은 ", h("b", { style: { color: "var(--ink)" } }, "기본 역할(방문자)"), "로 활성화됩니다. 방문자는 공개 노트만 열람할 수 있으며, 추가 권한은 ",
            h("b", { style: { color: "var(--ink)" } }, "권한 관리"), "에서 부여합니다."))),
      rows.length === 0
        ? h(window.Empty, { icon: "userCheck", title: "대기 중인 가입 신청이 없습니다", desc: "새 신청이 들어오면 이곳에 표시됩니다." })
        : h("div", { className: "table-wrap" },
            h("table", { className: "atable" },
              h("thead", null, h("tr", null,
                h("th", null, "사번"), h("th", null, "이메일"), h("th", null, "신청 일시"),
                h("th", null, "상태"), h("th", { className: "right" }, "처리"))),
              h("tbody", null,
                rows.map((r) => h("tr", { key: r.id },
                  h("td", { className: "mono" }, r.emp),
                  h("td", null, r.email),
                  h("td", { className: "muted" }, r.at),
                  h("td", null, h(window.StatusBadge, { status: r.status })),
                  h("td", { className: "right" },
                    h("div", { className: "actions" },
                      h("button", { className: "btn sm primary", onClick: () => act(r, "approve") }, "승인"),
                      h("button", { className: "btn sm danger", onClick: () => act(r, "reject") }, "반려")))))))),
      confirm && h(window.Modal, {
        icon: confirm.type === "approve" ? "userCheck" : "ban",
        iconWarn: confirm.type === "reject",
        title: confirm.type === "approve" ? "가입 승인" : "가입 반려",
        confirmLabel: confirm.type === "approve" ? "승인" : "반려",
        confirmDanger: confirm.type === "reject",
        onConfirm: apply, onClose: () => setConfirm(null),
      }, confirm.type === "approve"
        ? h("span", null, h("b", { className: "mono", style: { color: "var(--ink)" } }, confirm.row.emp), " 계정을 ", h("b", { style: { color: "var(--ink)" } }, "방문자"), " 역할로 활성화합니다. 계속할까요?")
        : h("span", null, h("b", { className: "mono", style: { color: "var(--ink)" } }, confirm.row.emp), " 가입 신청을 반려합니다. 신청자는 다시 가입을 요청해야 합니다."))
    );
  }

  // ============ 3. User management ============
  function Users({ toast }) {
    const [q, setQ] = useState("");
    const [role, setRole] = useState("all");
    const [status, setStatus] = useState("all");
    const [sel, setSel] = useState(null);     // selected user for detail panel
    const [confirm, setConfirm] = useState(null);
    const [users, setUsers] = useState(window.ADMIN_USERS.map((u) => ({ ...u })));

    const filtered = useMemo(() => users.filter((u) => {
      if (q && !(u.emp.toLowerCase().includes(q.toLowerCase()) || u.email.toLowerCase().includes(q.toLowerCase()))) return false;
      if (role !== "all" && u.role !== role) return false;
      if (status !== "all" && u.status !== status) return false;
      return true;
    }), [users, q, role, status]);

    const deactivate = () => {
      setUsers((us) => us.map((u) => u.id === confirm.id ? { ...u, status: "비활성" } : u));
      toast(confirm.emp + " 계정을 비활성화했습니다", "ban");
      setConfirm(null);
      setSel((s) => s && s.id === confirm.id ? { ...s, status: "비활성" } : s);
    };
    const resetPw = (u) => toast(u.emp + " 비밀번호를 초기화했습니다", "refresh");
    const grants = (uid) => window.ADMIN_GRANTS[uid] || {};

    return h("div", { className: "apage wide" },
      h(window.SecHead, { title: "사용자 관리", hint: filtered.length + "명" }),
      h("div", { className: "atoolbar" },
        h("div", { className: "afield" }, h(Icon, { name: "search" }),
          h("input", { placeholder: "사번 또는 이메일 검색", value: q, onChange: (e) => setQ(e.target.value) })),
        h("select", { className: "aselect", value: role, onChange: (e) => setRole(e.target.value) },
          h("option", { value: "all" }, "전체 역할"), h("option", null, "관리자"), h("option", null, "운영자"), h("option", null, "방문자")),
        h("select", { className: "aselect", value: status, onChange: (e) => setStatus(e.target.value) },
          h("option", { value: "all" }, "전체 상태"), h("option", null, "활성"), h("option", null, "비활성"), h("option", null, "대기"))),
      h("div", { style: { display: "grid", gridTemplateColumns: sel ? "1fr 340px" : "1fr", gap: 16, alignItems: "start" } },
        filtered.length === 0
          ? h(window.Empty, { icon: "users", title: "조건에 맞는 사용자가 없습니다", desc: "검색어나 필터를 조정해 보세요." })
          : h("div", { className: "table-wrap" },
              h("table", { className: "atable" },
                h("thead", null, h("tr", null,
                  h("th", null, "사번"), h("th", null, "이메일"), h("th", null, "역할"),
                  h("th", null, "상태"), h("th", null, "마지막 로그인"), h("th", { className: "right" }, "작업"))),
                h("tbody", null,
                  filtered.map((u) => h("tr", { key: u.id, style: { cursor: "default" } },
                    h("td", { className: "mono" }, u.emp),
                    h("td", null, u.email),
                    h("td", null, h(window.RoleBadge, { role: u.role })),
                    h("td", null, h(window.StatusBadge, { status: u.status })),
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
              h("div", { className: "row" }, h("span", { className: "k" }, "역할"), h("span", { className: "v" }, h(window.RoleBadge, { role: sel.role }))),
              h("div", { className: "row" }, h("span", { className: "k" }, "상태"), h("span", { className: "v" }, h(window.StatusBadge, { status: sel.status }))),
              h("div", { className: "row" }, h("span", { className: "k" }, "마지막 로그인"), h("span", { className: "v mono muted" }, sel.last))),
            h("div", { style: { marginTop: 18, marginBottom: 8, fontSize: 12, fontWeight: 600, color: "var(--text-2)" } }, "부여된 리소스 권한"),
            (function () {
              const g = grants(sel.id);
              const keys = Object.keys(g);
              const nameOf = (id) => { let n = id; window.walkAdminTree && window.walkAdminTree(window.ADMIN_TREE, (x) => { if (x.id === id) n = x.name; }); return n; };
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
      confirm && h(window.Modal, {
        icon: "ban", iconWarn: true, title: "계정 비활성화", confirmLabel: "비활성화", confirmDanger: true,
        onConfirm: deactivate, onClose: () => setConfirm(null),
      }, h("span", null, h("b", { className: "mono", style: { color: "var(--ink)" } }, confirm.emp), " 계정을 비활성화합니다. 해당 사용자는 로그인할 수 없게 됩니다. 계속할까요?"))
    );
  }

  // tree walker used above
  window.walkAdminTree = function (tree, cb) {
    tree.forEach((n) => { cb(n); if (n.children) window.walkAdminTree(n.children, cb); });
  };

  Object.assign(window, { AdminDashboard: Dashboard, AdminPending: Pending, AdminUsers: Users });
})();
