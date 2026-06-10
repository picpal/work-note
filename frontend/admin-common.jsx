/* Admin shared UI primitives + shell. Attaches components to window. */
(function () {
  const { useState, useEffect, useCallback } = React;
  const Icon = window.Icon;
  const h = React.createElement;

  // ---- Badge ----
  function StatusBadge({ status }) {
    const map = { "활성": "active", "비활성": "inactive", "대기": "pending" };
    return h("span", { className: "badge " + (map[status] || "pending") },
      h("span", { className: "bdot" }), status);
  }
  function RoleBadge({ role }) {
    const cls = role === "관리자" ? "role radmin" : "role";
    return h("span", { className: "badge " + cls }, role);
  }

  // ---- Modal ----
  function Modal({ icon, iconWarn, title, children, confirmLabel, confirmDanger, onConfirm, onClose }) {
    useEffect(() => {
      const k = (e) => { if (e.key === "Escape") onClose(); };
      document.addEventListener("keydown", k);
      return () => document.removeEventListener("keydown", k);
    }, []);
    return h("div", { className: "modal-ov", onMouseDown: onClose },
      h("div", { className: "modal", onMouseDown: (e) => e.stopPropagation() },
        h("div", { className: "modal-head" },
          h("div", { className: "micon" + (iconWarn ? " warn" : "") }, h(Icon, { name: icon || "alert" })),
          h("h3", null, title)),
        h("div", { className: "modal-body" }, children),
        h("div", { className: "modal-foot" },
          h("button", { className: "btn", onClick: onClose }, "취소"),
          h("button", { className: "btn " + (confirmDanger ? "danger" : "primary"), onClick: onConfirm }, confirmLabel || "확인"))));
  }

  // ---- Empty / Loading ----
  function Empty({ icon, title, desc }) {
    return h("div", { className: "empty" },
      h("div", { className: "ic" }, h(Icon, { name: icon || "fileLines" })),
      h("h3", null, title),
      desc && h("p", null, desc));
  }
  function SkeletonTable({ cols, rows }) {
    rows = rows || 5; cols = cols || 4;
    return h("div", { className: "table-wrap" },
      h("table", { className: "atable" },
        h("tbody", null,
          Array.from({ length: rows }).map((_, r) =>
            h("tr", { key: r }, Array.from({ length: cols }).map((_, c) =>
              h("td", { key: c }, h("div", { className: "skel", style: { height: 14, width: c === cols - 1 ? "40%" : "70%" } }))))))));
  }

  // ---- Switch ----
  function Switch({ on, onChange }) {
    return h("button", { className: "switch" + (on ? " on" : ""), onClick: () => onChange(!on), "aria-pressed": on });
  }

  // ---- Avatar (initials from emp id) ----
  function Avatar({ emp, cls }) {
    const t = (emp || "").replace(/[^0-9]/g, "").slice(-2) || "··";
    return h("span", { className: cls || "avatar" }, t);
  }

  // ---- Section header ----
  function SecHead({ title, hint, right }) {
    return h("div", { className: "asec-head" },
      h("h2", null, title),
      hint && h("span", { className: "hint" }, hint),
      h("span", { className: "spacer" }),
      right);
  }

  // ---- Toast (reused minimal) ----
  function useToast() {
    const [items, setItems] = useState([]);
    const push = useCallback((msg, icon) => {
      const id = Math.random().toString(36).slice(2);
      setItems((x) => [...x, { id, msg, icon }]);
      setTimeout(() => setItems((x) => x.filter((i) => i.id !== id)), 2400);
    }, []);
    const node = h("div", { className: "toast-wrap" },
      items.map((t) => h("div", { className: "toast", key: t.id },
        t.icon && h(Icon, { name: t.icon }), h("span", null, t.msg))));
    return [push, node];
  }

  Object.assign(window, { StatusBadge, RoleBadge, Modal, Empty, SkeletonTable, Switch, Avatar, SecHead, useToast });
})();
