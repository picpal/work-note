/* Admin screen 1: Dashboard */
import React from "react";
import { Icon } from "../../components/Icon";
import { ADMIN_USERS, ADMIN_PENDING, ADMIN_AUDIT } from "../data";
import { SecHead, Empty } from "../common";

const h = React.createElement;

export function Dashboard({ go, toast }: { go: (id: string) => void; toast: (msg: string, icon?: string) => void }) {
  const users = ADMIN_USERS;
  const pending = ADMIN_PENDING;
  const total = users.length;
  const active = users.filter((u) => u.status === "활성").length;
  const inactive = users.filter((u) => u.status === "비활성").length;
  const stats = [
    { label: "전체 사용자", num: total, delta: "운영자 3 · 방문자 3 · 관리자 1" },
    { label: "승인 대기", num: pending.length, delta: "신규 가입 신청" },
    { label: "활성 계정", num: active, delta: "최근 7일 로그인 5명" },
    { label: "비활성 계정", num: inactive, delta: "관리자 비활성 처리" },
  ];
  const actIcon: Record<string, string> = { login: "logout", grant: "key", approve: "userCheck", reset: "refresh", revoke: "ban", deactivate: "ban", loginfail: "alert" };
  const recent = ADMIN_AUDIT.slice(0, 5);

  return h("div", { className: "apage" },
    h(SecHead, { title: "개요", hint: "워크스페이스 사용자 및 접근 현황" }),
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
            ? h(Empty, { icon: "userCheck", title: "대기 중인 신청 없음" })
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
