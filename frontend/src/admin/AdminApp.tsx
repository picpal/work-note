/* AdminApp — shell: left nav, topbar, screen routing. */
import React from "react";
import { Icon } from "../components/Icon";
import { ADMIN_PENDING } from "./data";
import { useToast } from "./common";
import { Dashboard } from "./screens/Dashboard";
import { Pending } from "./screens/Pending";
import { Users } from "./screens/Users";
import { Permissions } from "./screens/Permissions";
import { Roles } from "./screens/Roles";
import { Audit } from "./screens/Audit";
import { Security } from "./screens/Security";

const { useState, useEffect } = React;
const h = React.createElement;

const NAV = [
  { id: "dashboard", label: "대시보드", icon: "gauge" },
  { id: "pending", label: "가입 승인 대기", icon: "userCheck", badge: () => ADMIN_PENDING.length },
  { id: "users", label: "사용자 관리", icon: "users" },
  { id: "permissions", label: "권한 관리", icon: "key" },
  { id: "roles", label: "역할 관리", icon: "roles" },
  { id: "audit", label: "감사 로그", icon: "history" },
  { id: "security", label: "보안 설정", icon: "settings" },
];
const TITLES: Record<string, [string, string]> = {
  dashboard: ["대시보드", "워크스페이스 운영 현황"],
  pending: ["가입 승인 대기", "신규 가입 신청 처리"],
  users: ["사용자 관리", "계정·역할·상태 관리"],
  permissions: ["권한 관리", "리소스 단위 접근 권한 부여"],
  roles: ["역할 관리", "역할별 기본 정책"],
  audit: ["감사 로그", "보안 감사 추적"],
  security: ["보안 설정", "인증·세션 정책"],
};

export function AdminApp() {
  const [route, setRoute] = useState(() => (location.hash || "#dashboard").slice(1));
  const [toastPush, toastNode] = useToast();

  useEffect(() => {
    const onHash = () => setRoute((location.hash || "#dashboard").slice(1));
    window.addEventListener("hashchange", onHash);
    return () => window.removeEventListener("hashchange", onHash);
  }, []);
  const go = (id: string) => { location.hash = id; setRoute(id); };

  const screenMap: Record<string, React.ComponentType<{ go: (id: string) => void; toast: (msg: string, icon?: string) => void }>> = {
    dashboard: Dashboard,
    pending: Pending,
    users: Users,
    permissions: Permissions,
    roles: Roles,
    audit: Audit,
    security: Security,
  };
  const Screen = screenMap[route] || Dashboard;

  const [title, sub] = TITLES[route] || TITLES.dashboard;

  return h("div", { className: "admin" },
    // left nav
    h("aside", { className: "anav" },
      h("div", { className: "anav-top" },
        h("div", { className: "anav-brand" },
          h("div", { className: "brand-mark" }, "W"),
          h("div", { className: "name" }, "WorkNote"),
          h("span", { className: "tag" }, "관리자"))),
      h("nav", { className: "anav-list" },
        NAV.map((n) => h("button", {
          key: n.id, className: "anav-item" + (route === n.id ? " active" : ""), onClick: () => go(n.id),
        },
          h("span", { className: "ic" }, h(Icon, { name: n.icon })),
          h("span", null, n.label),
          n.badge && n.badge() > 0 && h("span", { className: "count" }, n.badge())))),
      h("div", { className: "anav-foot" },
        h("a", { className: "anav-back", href: "index.html" },
          h(Icon, { name: "arrowLeft" }), "노트로 돌아가기"))),
    // main
    h("div", { className: "amain" },
      h("div", { className: "atopbar" },
        h("div", null,
          h("h1", null, title),
          h("div", { className: "sub" }, sub)),
        h("div", { className: "right" },
          h("button", { className: "icon-btn", title: "테마", onClick: () => {
            const cur = document.documentElement.getAttribute("data-theme") === "dark" ? "light" : "dark";
            document.documentElement.setAttribute("data-theme", cur);
            try { localStorage.setItem("wn.theme", cur); } catch (e) {}
            setRoute((r) => r); // re-render
          } }, h(Icon, { name: document.documentElement.getAttribute("data-theme") === "dark" ? "sun" : "moon" })))),
      h("div", { className: "ascroll" },
        h(Screen, { go, toast: toastPush }))),
    toastNode);
}
