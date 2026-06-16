/* AdminApp — shell: left nav, topbar, screen routing. */
import React from "react";
import { Icon } from "../components/Icon";
import { ApiError } from "../api/http";
import { AuthApi, type Me } from "../api/auth";
import { AdminApi, type ApiRole, type ApiTeam, type ApiUser } from "./api";
import { AdminDataContext } from "./useAdminData";
import { useToast } from "./common";
import { Dashboard } from "./screens/Dashboard";
import { Pending } from "./screens/Pending";
import { Users } from "./screens/Users";
import { Permissions } from "./screens/Permissions";
import { Roles } from "./screens/Roles";
import { Teams } from "./screens/Teams";
import { Shares } from "./screens/Shares";
import { Audit } from "./screens/Audit";
import { Pii } from "./screens/Pii";
import { Uploads } from "./screens/Uploads";
import { Security } from "./screens/Security";

const { useState, useEffect, useCallback, useMemo } = React;
const h = React.createElement;

const NAV = [
  { id: "dashboard", label: "대시보드", icon: "gauge" },
  { id: "pending", label: "가입 승인 대기", icon: "userCheck" },
  { id: "users", label: "사용자 관리", icon: "users" },
  { id: "permissions", label: "권한 관리", icon: "key" },
  { id: "roles", label: "역할 관리", icon: "roles" },
  { id: "teams", label: "팀·스페이스", icon: "users" },
  { id: "shares", label: "공유 링크", icon: "link" },
  { id: "pii", label: "개인정보 점검", icon: "alert" },
  { id: "audit", label: "감사 로그", icon: "history" },
  { id: "uploads", label: "업로드 정책", icon: "image" },
  { id: "security", label: "보안 설정", icon: "settings" },
];
const TITLES: Record<string, [string, string]> = {
  dashboard: ["대시보드", "워크스페이스 운영 현황"],
  pending: ["가입 승인 대기", "신규 가입 신청 처리"],
  users: ["사용자 관리", "계정·역할·상태 관리"],
  permissions: ["권한 관리", "리소스 단위 접근 권한 부여"],
  roles: ["역할 관리", "역할별 기본 정책"],
  teams: ["팀·스페이스", "팀 구성·팀 스페이스 관리"],
  shares: ["공유 링크", "활성 공유 링크 조회·취소"],
  pii: ["개인정보 점검", "PII 탐지 노트·예외 요청 처리"],
  audit: ["감사 로그", "보안 감사 추적"],
  uploads: ["업로드 정책", "첨부 허용 확장자·용량"],
  security: ["보안 설정", "인증·세션 정책"],
};

export function AdminApp() {
  const [route, setRoute] = useState(() => (location.hash || "#dashboard").slice(1));
  const [navCollapsed, setNavCollapsed] = useState(() => {
    try { return localStorage.getItem("wn.adminNavCollapsed") === "1"; } catch (e) { return false; }
  });
  const applyCollapsed = useCallback((next: boolean) => {
    setNavCollapsed(next);
    try { localStorage.setItem("wn.adminNavCollapsed", next ? "1" : "0"); } catch (e) {}
  }, []);
  const toggleCollapsed = useCallback(() => {
    setNavCollapsed((c) => {
      const next = !c;
      try { localStorage.setItem("wn.adminNavCollapsed", next ? "1" : "0"); } catch (e) {}
      return next;
    });
  }, []);
  const [toastPush, toastNode] = useToast();
  const [me, setMe] = useState<Me | null>(null);
  const [data, setData] = useState<{ users: ApiUser[]; roles: ApiRole[]; teams: ApiTeam[] } | null>(null);

  const reload = useCallback(async () => {
    try {
      const [users, roles, teams] = await Promise.all([AdminApi.users(), AdminApi.roles(), AdminApi.teams()]);
      setData({ users, roles, teams });
    } catch (e) {
      toastPush(e instanceof ApiError ? e.message : "데이터를 불러오지 못했습니다");
    }
  }, [toastPush]);

  // 인증 가드 — me 조회 후 admin 권한 확인. 401은 전역 on401(login.html)이 처리.
  useEffect(() => {
    let alive = true;
    AuthApi.me()
      .then((m) => {
        if (!alive) return;
        if (!m.caps.includes("admin.users")) { location.href = "index.html"; return; }
        setMe(m);
        void reload();
      })
      .catch((e) => {
        if (!alive) return;
        if (e instanceof ApiError && e.status === 401) return; // on401이 리다이렉트
        toastPush("서버에 연결할 수 없습니다");
      });
    return () => { alive = false; };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  useEffect(() => {
    const onHash = () => setRoute((location.hash || "#dashboard").slice(1));
    window.addEventListener("hashchange", onHash);
    return () => window.removeEventListener("hashchange", onHash);
  }, []);

  // 전역 단축키 — 메인과 동일하게 Ctrl/⌘+\ 로 사이드바 토글.
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if ((e.metaKey || e.ctrlKey) && e.key === "\\") { e.preventDefault(); toggleCollapsed(); }
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [toggleCollapsed]);
  const go = (id: string) => { location.hash = id; setRoute(id); };

  const screenMap: Record<string, React.ComponentType<{ go: (id: string) => void; toast: (msg: string, icon?: string) => void }>> = {
    dashboard: Dashboard,
    pending: Pending,
    users: Users,
    permissions: Permissions,
    roles: Roles,
    teams: Teams,
    shares: Shares,
    pii: Pii,
    audit: Audit,
    uploads: Uploads,
    security: Security,
  };
  const Screen = screenMap[route] || Dashboard;

  const [title, sub] = TITLES[route] || TITLES.dashboard;

  const pendingCount = data ? data.users.filter((u) => u.status === "pending").length : 0;
  const adminData = useMemo(() => ({
    me,
    users: data?.users ?? [],
    roles: data?.roles ?? [],
    teams: data?.teams ?? [],
    reload,
    toast: toastPush,
  }), [me, data, reload, toastPush]);
  const loading = !me || !data;

  return h("div", { className: "admin" + (navCollapsed ? " anav-collapsed" : "") },
    // left nav
    h("aside", { className: "anav" },
      h("div", { className: "anav-top" },
        h("div", { className: "anav-brand" },
          h("div", { className: "brand-mark" }, "W"),
          h("div", { className: "name" }, "WorkNote"),
          h("span", { className: "tag" }, "관리자")),
        h("button", { className: "icon-btn anav-collapse-btn", title: "사이드바 접기", onClick: () => applyCollapsed(true) },
          h(Icon, { name: "panelLeft" }))),
      h("nav", { className: "anav-list" },
        NAV.map((n) => h("button", {
          key: n.id, className: "anav-item" + (route === n.id ? " active" : ""), onClick: () => go(n.id),
        },
          h("span", { className: "ic" }, h(Icon, { name: n.icon })),
          h("span", null, n.label),
          n.id === "pending" && pendingCount > 0 && h("span", { className: "count" }, pendingCount)))),
      h("div", { className: "anav-foot" },
        h("a", { className: "anav-back", href: "index.html" },
          h(Icon, { name: "arrowLeft" }), "노트로 돌아가기"))),
    // main
    h("div", { className: "amain" },
      h("div", { className: "atopbar" },
        navCollapsed && h("button", { className: "icon-btn", title: "사이드바 펼치기", onClick: () => applyCollapsed(false) },
          h(Icon, { name: "panelLeft" })),
        h("div", null,
          h("h1", null, title),
          h("div", { className: "sub" }, sub)),
        h("div", { className: "right" },
          h("button", { className: "icon-btn", title: "테마", onClick: () => {
            const cur = document.documentElement.getAttribute("data-theme") === "dark" ? "light" : "dark";
            document.documentElement.setAttribute("data-theme", cur);
            try { localStorage.setItem("wn.theme", cur); } catch (e) {}
            setRoute((r) => r); // re-render
          } }, h(Icon, { name: document.documentElement.getAttribute("data-theme") === "dark" ? "sun" : "moon" })),
          me && h("button", { className: "icon-btn", title: "로그아웃", onClick: () => {
            AuthApi.logout().finally(() => { location.href = "login.html"; });
          } }, h(Icon, { name: "logout" })))),
      h("div", { className: "ascroll" },
        loading
          ? h("div", { className: "aload", style: { padding: "40px 0", textAlign: "center", color: "var(--text-3)", fontSize: 13 } }, "불러오는 중…")
          : h(AdminDataContext.Provider, { value: adminData },
              h(Screen, { go, toast: toastPush })))),
    toastNode);
}
