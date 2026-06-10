/* Admin screens 4-7: Permissions, Roles, Audit log, Security settings */
(function () {
  const { useState, useMemo } = React;
  const Icon = window.Icon;
  const h = React.createElement;

  // ============ 4. Permission management ============
  function PermNode({ node, depth, grants, onToggle, pub, onPub }) {
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

  function Permissions({ toast }) {
    const [view, setView] = useState("user");   // user | resource
    const [uid, setUid] = useState("u3");
    const [uq, setUq] = useState("");
    const [grants, setGrants] = useState(() => JSON.parse(JSON.stringify(window.ADMIN_GRANTS)));
    const [pub, setPub] = useState(() => ({ ...window.ADMIN_PUBLIC }));
    const [dirty, setDirty] = useState(0);

    const users = window.ADMIN_USERS.filter((u) => u.role !== "관리자");
    const fUsers = users.filter((u) => !uq || u.emp.toLowerCase().includes(uq.toLowerCase()) || u.email.toLowerCase().includes(uq.toLowerCase()));
    const userGrants = grants[uid] || {};

    // compute inheritance for display
    const effective = useMemo(() => {
      const out = JSON.parse(JSON.stringify(userGrants));
      const walk = (nodes, parent) => {
        nodes.forEach((n) => {
          const g = out[n.id] || (out[n.id] = {});
          if (parent) {
            if (parent.read && !g.read) g.inheritedRead = true;
            if (parent.edit && !g.edit) g.inheritedEdit = true;
          }
          if (n.children) walk(n.children, { read: g.read || g.inheritedRead, edit: g.edit || g.inheritedEdit });
        });
      };
      walk(window.ADMIN_TREE, null);
      return out;
    }, [userGrants, dirty]);

    const toggle = (nodeId, kind) => {
      setGrants((gr) => {
        const next = JSON.parse(JSON.stringify(gr));
        const u = next[uid] || (next[uid] = {});
        const g = u[nodeId] || (u[nodeId] = {});
        g[kind] = !g[kind];
        if (kind === "edit" && g.edit) g.read = true;       // edit implies read
        if (kind === "read" && !g.read) g.edit = false;     // remove read removes edit
        // mark override if this is a note under a granted folder
        g.override = !!(g.read || g.edit);
        if (!g.read && !g.edit) delete u[nodeId];
        return next;
      });
      setDirty((d) => d + 1);
    };
    const togglePub = (nodeId) => { setPub((p) => ({ ...p, [nodeId]: !p[nodeId] })); setDirty((d) => d + 1); };
    const commit = () => { setDirty(0); toast("권한 변경 사항을 적용했습니다", "check"); };

    const selUser = window.ADMIN_USERS.find((u) => u.id === uid);

    return h("div", { className: "apage wide" },
      h(window.SecHead, {
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
                h(Icon, { name: "search" }), h("input", { placeholder: "사용자 검색", value: uq, onChange: (e) => setUq(e.target.value) })),
              h("div", { className: "upick" },
                fUsers.map((u) => h("div", { className: "upick-item" + (u.id === uid ? " active" : ""), key: u.id, onClick: () => setUid(u.id) },
                  h(window.Avatar, { emp: u.emp }),
                  h("div", { className: "info" },
                    h("div", { className: "emp mono" }, u.emp),
                    h("div", { className: "mail" }, u.email)),
                  h("span", { className: "badge role" + (u.role === "관리자" ? " radmin" : "") }, u.role))))),
            // tree
            h("div", { className: "panel" },
              h("div", { className: "panel-head" },
                h(window.Avatar, { emp: selUser.emp, cls: "avatar" }),
                h("span", null, h("span", { className: "mono", style: { color: "var(--ink)" } }, selUser.emp), " 의 리소스 권한"),
                h("span", { style: { marginLeft: "auto", fontSize: 12, color: "var(--text-3)", fontWeight: 400 } }, "폴더 권한은 하위로 상속됩니다")),
              h("div", { className: "panel-body" },
                h("div", { className: "ptree" },
                  window.ADMIN_TREE.map((n) => h(PermNode, { key: n.id, node: n, depth: 0, grants: effective, onToggle: toggle, pub, onPub: togglePub }))),
                h("div", { style: { marginTop: 14, paddingTop: 14, borderTop: "1px solid var(--border-soft)" } },
                  h("div", { style: { fontSize: 12, fontWeight: 600, color: "var(--text-2)", marginBottom: 10 } }, "공개(Public) 설정 — 공개 시 방문자 포함 모두 열람"),
                  h("div", { style: { display: "flex", flexDirection: "column", gap: 4 } },
                    flattenForPublic(window.ADMIN_TREE).map((n) => h("div", { key: n.id, style: { display: "flex", alignItems: "center", gap: 9, padding: "5px 8px", borderRadius: 6 } },
                      h("span", { style: { color: "var(--text-3)", display: "grid", placeItems: "center" } }, h(Icon, { name: n.type === "folder" ? "folder" : "fileLines" })),
                      h("span", { style: { flex: 1, fontSize: 13, color: "var(--text)" } }, n.name),
                      h(window.Switch, { on: !!pub[n.id], onChange: () => togglePub(n.id) }))))),
                dirty > 0 && h("div", { className: "changebar" },
                  h(Icon, { name: "alert" }),
                  h("span", { className: "txt" }, h("b", null, dirty + "건"), "의 변경 사항이 적용 대기 중입니다"),
                  h("span", { className: "spacer" }),
                  h("button", { className: "btn", onClick: () => { setGrants(JSON.parse(JSON.stringify(window.ADMIN_GRANTS))); setPub({ ...window.ADMIN_PUBLIC }); setDirty(0); } }, "되돌리기"),
                  h("button", { className: "btn primary", onClick: commit }, "변경 적용")))))
        : h(ResourceView, null)
    );
  }

  function flattenForPublic(tree, out) {
    out = out || [];
    tree.forEach((n) => { out.push(n); });
    return out;
  }

  // resource-centric view
  function ResourceView() {
    const [nodeId, setNodeId] = useState("n-pipe");
    const all = []; window.walkAdminTree(window.ADMIN_TREE, (n) => all.push(n));
    const node = all.find((n) => n.id === nodeId);
    // who can access this resource (derived from grants)
    const accessors = window.ADMIN_USERS.filter((u) => {
      const g = (window.ADMIN_GRANTS[u.id] || {});
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
                h("td", null, h(window.RoleBadge, { role: u.role })),
                h("td", { className: "right" }, h("span", { className: "badge role" }, u.role === "관리자" ? "전체" : ((window.ADMIN_GRANTS[u.id] || {})[nodeId]?.edit || (window.ADMIN_GRANTS[u.id] || {})["f-arch"]?.edit) ? "읽기+편집" : "읽기")))))))));
  }

  // ============ 5. Roles ============
  function RoleEditor({ initial, isNew, existingNames, onSave, onClose }) {
    const [name, setName] = useState(initial.name || "");
    const [desc, setDesc] = useState(initial.desc || "");
    const [policy, setPolicy] = useState(initial.policy ? [...initial.policy] : []);
    const [draft, setDraft] = useState("");
    const [err, setErr] = useState("");
    const locked = initial.system; // system role name can't be renamed

    const addChip = () => {
      const v = draft.trim();
      if (!v) return;
      if (!policy.includes(v)) setPolicy((p) => [...p, v]);
      setDraft("");
    };
    const save = () => {
      const nm = name.trim();
      if (!nm) { setErr("역할 이름을 입력하세요."); return; }
      if (existingNames.filter((x) => x !== initial.name).includes(nm)) { setErr("이미 존재하는 역할 이름입니다."); return; }
      onSave({ ...initial, name: nm, desc: desc.trim(), policy });
    };

    return h("div", { className: "modal-ov", onMouseDown: onClose },
      h("div", { className: "modal", style: { width: "min(540px, 94vw)" }, onMouseDown: (e) => e.stopPropagation() },
        h("div", { className: "modal-head" },
          h("div", { className: "micon" }, h(Icon, { name: "roles" })),
          h("h3", null, isNew ? "역할 추가" : "역할 편집")),
        h("div", { className: "modal-body", style: { paddingLeft: 20, paddingRight: 20 } },
          h("div", { className: "field" },
            h("label", { className: "flabel" }, "역할 이름", locked ? " (시스템 역할 — 변경 불가)" : ""),
            h("input", { className: "tinput", value: name, disabled: locked, placeholder: "예: 검토자",
              onChange: (e) => { setName(e.target.value); setErr(""); } })),
          h("div", { className: "field" },
            h("label", { className: "flabel" }, "설명"),
            h("textarea", { className: "tarea", value: desc, placeholder: "이 역할의 기본 정책을 설명하세요",
              onChange: (e) => setDesc(e.target.value) })),
          h("div", { className: "field" },
            h("label", { className: "flabel" }, "정책 (권한 항목)"),
            h("div", { className: "chip-wrap" },
              policy.map((p, i) => h("span", { className: "chip chip-edit", key: i }, p,
                h("button", { title: "삭제", onClick: () => setPolicy((arr) => arr.filter((_, j) => j !== i)) }, "×"))),
              h("input", { className: "chip-add", value: draft, placeholder: "+ 항목 추가",
                onChange: (e) => setDraft(e.target.value),
                onKeyDown: (e) => { if (e.key === "Enter" || e.key === ",") { e.preventDefault(); addChip(); } },
                onBlur: addChip }))),
          err && h("div", { style: { color: "#b3261e", fontSize: 12.5, marginTop: 12 } }, err)),
        h("div", { className: "modal-foot" },
          h("button", { className: "btn", onClick: onClose }, "취소"),
          h("button", { className: "btn primary", onClick: save }, isNew ? "추가" : "저장"))));
  }

  function Roles({ toast }) {
    const [roles, setRoles] = useState(() => window.ADMIN_ROLES.map((r) => ({ ...r, policy: [...r.policy] })));
    const [editing, setEditing] = useState(null); // role obj | {__new:true} | null

    const onSave = (role) => {
      if (role.__new) {
        const id = "role-" + Date.now().toString(36);
        setRoles((rs) => [...rs, { id, name: role.name, desc: role.desc, policy: role.policy, system: false, count: 0 }]);
        toast("역할 \"" + role.name + "\" 을(를) 추가했습니다", "check");
      } else {
        setRoles((rs) => rs.map((r) => r.id === role.id ? { ...r, name: role.name, desc: role.desc, policy: role.policy } : r));
        toast("역할 \"" + role.name + "\" 을(를) 저장했습니다", "check");
      }
      setEditing(null);
    };

    return h("div", { className: "apage" },
      h(window.SecHead, { title: "역할 관리", hint: "역할별 기본 정책",
        right: h("button", { className: "btn primary", onClick: () => setEditing({ __new: true, policy: [] }) },
          h(Icon, { name: "plus" }), "역할 추가") }),
      h("div", { className: "role-list" },
        roles.map((r) => h("div", { className: "role-card", key: r.id },
          h("div", { className: "rc-head" },
            h("span", { className: "rc-name" }, r.name),
            r.system && h("span", { className: "badge role" }, "시스템"),
            h("span", { className: "badge role" }, r.count + "명"),
            h("span", { style: { marginLeft: "auto" } },
              h("button", { className: "btn sm", onClick: () => setEditing(r) }, h(Icon, { name: "edit" }), "편집"))),
          h("div", { className: "rc-desc" }, r.desc),
          h("div", { className: "rc-policy" }, r.policy.map((p, i) => h("span", { className: "chip", key: i }, p)))))),
      editing && h(RoleEditor, {
        initial: editing.__new ? { __new: true, name: "", desc: "", policy: [], system: false } : editing,
        isNew: !!editing.__new,
        existingNames: roles.map((r) => r.name),
        onSave, onClose: () => setEditing(null),
      })
    );
  }

  // ============ 6. Audit log ============
  function adminDownload(filename, text, mime) {
    const blob = new Blob(["\uFEFF" + text], { type: (mime || "text/plain") + ";charset=utf-8" });
    const url = URL.createObjectURL(blob);
    const a = document.createElement("a");
    a.href = url; a.download = filename;
    document.body.appendChild(a); a.click();
    setTimeout(() => { document.body.removeChild(a); URL.revokeObjectURL(url); }, 100);
  }

  function Audit({ toast }) {
    const [who, setWho] = useState("");
    const [type, setType] = useState("all");
    const data = window.ADMIN_AUDIT;
    const types = [["all", "전체 행위"], ["login", "로그인"], ["grant", "권한 부여"], ["revoke", "권한 회수"], ["approve", "계정 승인"], ["reset", "비번 초기화"], ["deactivate", "계정 비활성화"], ["loginfail", "로그인 실패"]];
    const rows = data.filter((r) => (!who || r.who.toLowerCase().includes(who.toLowerCase())) && (type === "all" || r.actType === type));

    const stamp = () => new Date().toISOString().slice(0, 19).replace("T", " ");
    const fileStamp = () => new Date().toISOString().slice(0, 10);

    const exportCsv = () => {
      const esc = (s) => '"' + String(s).replace(/"/g, '""') + '"';
      const head = ["일시", "행위자", "행위", "대상", "IP/단말"];
      const lines = [head.map(esc).join(",")].concat(
        rows.map((r) => [r.at, r.who, r.act, r.target, r.ip].map(esc).join(",")));
      adminDownload("audit-log_" + fileStamp() + ".csv", lines.join("\r\n"), "text/csv");
      toast && toast(rows.length + "건을 CSV로 내보냈습니다", "download");
    };

    const downloadReport = () => {
      const byType = {};
      data.forEach((r) => { byType[r.act] = (byType[r.act] || 0) + 1; });
      const fails = data.filter((r) => r.actType === "loginfail").length;
      const grants = data.filter((r) => r.actType === "grant" || r.actType === "revoke").length;
      const period = data.length ? (data[data.length - 1].at.slice(0, 10) + " ~ " + data[0].at.slice(0, 10)) : "—";
      let md = "";
      md += "# WorkNote 감사 리포트\n\n";
      md += "- 생성 일시: " + stamp() + "\n";
      md += "- 대상 기간: " + period + "\n";
      md += "- 총 이벤트: " + data.length + "건\n";
      md += "- 권한 변경(부여/회수): " + grants + "건\n";
      md += "- 로그인 실패: " + fails + "건\n\n";
      md += "## 행위 유형별 집계\n\n";
      md += "| 행위 | 건수 |\n| --- | --- |\n";
      Object.keys(byType).forEach((k) => { md += "| " + k + " | " + byType[k] + " |\n"; });
      md += "\n## 전체 로그 (시간 역순)\n\n";
      md += "| 일시 | 행위자 | 행위 | 대상 | IP/단말 |\n| --- | --- | --- | --- | --- |\n";
      data.forEach((r) => { md += "| " + r.at + " | " + r.who + " | " + r.act + " | " + (r.target || "—") + " | " + r.ip + " |\n"; });
      md += "\n---\n_본 리포트는 ISMS · PCI-DSS 감사 추적 목적으로 자동 생성되었습니다._\n";
      adminDownload("audit-report_" + fileStamp() + ".md", md, "text/markdown");
      toast && toast("감사 리포트를 내려받았습니다", "check");
    };

    return h("div", { className: "apage wide" },
      h(window.SecHead, { title: "감사 로그", hint: "ISMS · PCI-DSS 추적용 · 시간 역순" }),
      h("div", { className: "atoolbar" },
        h("div", { className: "afield" }, h(Icon, { name: "search" }),
          h("input", { placeholder: "행위자(사번) 검색", value: who, onChange: (e) => setWho(e.target.value) })),
        h("select", { className: "aselect", value: type, onChange: (e) => setType(e.target.value) },
          types.map((t) => h("option", { key: t[0], value: t[0] }, t[1]))),
        h("span", { style: { flex: 1 } }),
        h("button", { className: "btn", onClick: downloadReport }, h(Icon, { name: "fileLines" }), "감사 리포트"),
        h("button", { className: "btn", onClick: exportCsv }, h(Icon, { name: "download" }), "내보내기")),
      rows.length === 0
        ? h(window.Empty, { icon: "history", title: "조건에 맞는 로그가 없습니다" })
        : h("div", { className: "table-wrap" },
            h("table", { className: "atable" },
              h("thead", null, h("tr", null,
                h("th", null, "일시"), h("th", null, "행위자"), h("th", null, "행위"),
                h("th", null, "대상"), h("th", null, "IP / 단말"))),
              h("tbody", null,
                rows.map((r, i) => h("tr", { key: i },
                  h("td", { className: "mono muted" }, r.at),
                  h("td", { className: "mono" }, r.who),
                  h("td", null, h("span", { style: { fontWeight: 550, color: r.actType === "loginfail" ? "#b3261e" : "var(--ink)" } }, r.act)),
                  h("td", { className: "muted" }, r.target),
                  h("td", { className: "mono muted" }, r.ip))))))
    );
  }

  // ============ 7. Security settings ============
  function Security({ toast }) {
    const [s, setS] = useState(() => ({ ...window.ADMIN_SECURITY }));
    const [dirty, setDirty] = useState(false);
    const set = (k, v) => { setS((p) => ({ ...p, [k]: v })); setDirty(true); };
    const save = () => { setDirty(false); toast("보안 설정을 저장했습니다", "check"); };

    const numRow = (title, desc, key, unit, min, max) => h("div", { className: "frow" },
      h("div", { className: "fmeta" }, h("div", { className: "ft" }, title), h("div", { className: "fd" }, desc)),
      h("div", { className: "fctl" },
        h("input", { className: "num-input", type: "number", value: s[key], min, max, onChange: (e) => set(key, Number(e.target.value)) }),
        unit && h("span", { className: "unit" }, unit)));
    const toggleRow = (title, desc, key) => h("div", { className: "frow" },
      h("div", { className: "fmeta" }, h("div", { className: "ft" }, title), h("div", { className: "fd" }, desc)),
      h("div", { className: "fctl" }, h(window.Switch, { on: s[key], onChange: (v) => set(key, v) })));

    return h("div", { className: "apage" },
      h(window.SecHead, { title: "보안 설정", hint: "ISMS 정책 항목" }),
      h("div", { className: "panel" },
        h("div", { className: "panel-head" }, h(Icon, { name: "lock" }), "비밀번호 정책"),
        h("div", { className: "panel-body" },
          numRow("최소 길이", "비밀번호 최소 문자 수", "pwMinLen", "자", 6, 32),
          toggleRow("복잡도 요구", "영문 대/소문자·숫자·특수문자 조합 필수", "pwComplexity"),
          numRow("변경 주기", "비밀번호 강제 변경 주기 (0이면 미사용)", "pwRotateDays", "일", 0, 365))),
      h("div", { className: "panel", style: { marginTop: 16 } },
        h("div", { className: "panel-head" }, h(Icon, { name: "shield" }), "접근 · 세션"),
        h("div", { className: "panel-body" },
          numRow("로그인 실패 잠금", "연속 실패 시 계정 잠금 횟수", "lockAttempts", "회", 1, 10),
          numRow("세션 타임아웃", "유휴 상태 자동 로그아웃 시간", "sessionTimeout", "분", 5, 240),
          toggleRow("신규 가입 관리자 승인 필수", "가입 신청을 관리자가 승인해야 계정이 활성화됩니다", "requireApproval"))),
      h("div", { className: "btn-row", style: { marginTop: 18, justifyContent: "flex-end" } },
        h("button", { className: "btn", disabled: !dirty, onClick: () => { setS({ ...window.ADMIN_SECURITY }); setDirty(false); } }, "되돌리기"),
        h("button", { className: "btn primary", disabled: !dirty, onClick: save }, "변경 사항 저장"))
    );
  }

  Object.assign(window, { AdminPermissions: Permissions, AdminRoles: Roles, AdminAudit: Audit, AdminSecurity: Security });
})();
