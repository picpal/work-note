/* Admin shared UI primitives + shell. */
import React from "react";
import { Icon } from "../components/Icon";

const { useState, useEffect, useCallback } = React;
const h = React.createElement;

// ---- Badge ----
export function StatusBadge({ status }: { status: string }) {
  const map: Record<string, string> = { "활성": "active", "비활성": "inactive", "대기": "pending" };
  return h("span", { className: "badge " + (map[status] || "pending") },
    h("span", { className: "bdot" }), status);
}

export function RoleBadge({ role }: { role: string }) {
  const cls = role === "관리자" ? "role radmin" : "role";
  return h("span", { className: "badge " + cls }, role);
}

// ---- Modal ----
export function Modal({ icon, iconWarn, title, children, confirmLabel, confirmDanger, onConfirm, onClose }: {
  icon?: string;
  iconWarn?: boolean;
  title: string;
  children?: React.ReactNode;
  confirmLabel?: string;
  confirmDanger?: boolean;
  onConfirm?: () => void;
  onClose: () => void;
}) {
  useEffect(() => {
    const k = (e: KeyboardEvent) => { if (e.key === "Escape") onClose(); };
    document.addEventListener("keydown", k);
    return () => document.removeEventListener("keydown", k);
  }, []);
  return h("div", { className: "modal-ov", onMouseDown: onClose },
    h("div", { className: "modal", onMouseDown: (e: React.MouseEvent) => e.stopPropagation() },
      h("div", { className: "modal-head" },
        h("div", { className: "micon" + (iconWarn ? " warn" : "") }, h(Icon, { name: icon || "alert" })),
        h("h3", null, title)),
      h("div", { className: "modal-body" }, children),
      h("div", { className: "modal-foot" },
        h("button", { className: "btn", onClick: onClose }, onConfirm ? "취소" : "닫기"),
        onConfirm && h("button", { className: "btn " + (confirmDanger ? "danger" : "primary"), onClick: onConfirm }, confirmLabel || "확인"))));
}

// ---- Empty / Loading ----
export function Empty({ icon, title, desc }: { icon?: string; title: string; desc?: string }) {
  return h("div", { className: "empty" },
    h("div", { className: "ic" }, h(Icon, { name: icon || "fileLines" })),
    h("h3", null, title),
    desc && h("p", null, desc));
}

export function SkeletonTable({ cols, rows }: { cols?: number; rows?: number }) {
  rows = rows || 5; cols = cols || 4;
  return h("div", { className: "table-wrap" },
    h("table", { className: "atable" },
      h("tbody", null,
        Array.from({ length: rows }).map((_, r) =>
          h("tr", { key: r }, Array.from({ length: cols as number }).map((_, c) =>
            h("td", { key: c }, h("div", { className: "skel", style: { height: 14, width: c === (cols as number) - 1 ? "40%" : "70%" } }))))))));
}

// ---- Switch ----
export function Switch({ on, onChange }: { on: boolean; onChange: (v: boolean) => void }) {
  return h("button", { className: "switch" + (on ? " on" : ""), onClick: () => onChange(!on), "aria-pressed": on });
}

// ---- Avatar (initials from emp id) ----
export function Avatar({ emp, cls }: { emp?: string; cls?: string }) {
  const t = (emp || "").replace(/[^0-9]/g, "").slice(-2) || "··";
  return h("span", { className: cls || "avatar" }, t);
}

// ---- Section header ----
export function SecHead({ title, hint, right }: { title: string; hint?: string; right?: React.ReactNode }) {
  return h("div", { className: "asec-head" },
    h("h2", null, title),
    hint && h("span", { className: "hint" }, hint),
    h("span", { className: "spacer" }),
    right);
}

// ---- Toast (reused minimal) ----
export function useToast(): [(msg: string, icon?: string) => void, React.ReactElement] {
  const [items, setItems] = useState<{ id: string; msg: string; icon?: string }[]>([]);
  const push = useCallback((msg: string, icon?: string) => {
    const id = Math.random().toString(36).slice(2);
    setItems((x) => [...x, { id, msg, icon }]);
    setTimeout(() => setItems((x) => x.filter((i) => i.id !== id)), 2400);
  }, []);
  const node = h("div", { className: "toast-wrap" },
    items.map((t) => h("div", { className: "toast", key: t.id },
      t.icon && h(Icon, { name: t.icon }), h("span", null, t.msg))));
  return [push, node];
}
