/* Admin screen 1: Dashboard — 실데이터(useAdminData) 통계 + AdminApi.audit 최근 활동. */
import React from "react";
import { Icon } from "../../components/Icon";
import { AdminApi, ApiAudit } from "../api";
import { actLabel, actType } from "../mappers";
import { ApiError } from "../../api/http";
import { SecHead, Empty } from "../common";
import { useAdminData } from "../useAdminData";

const { useState, useEffect } = React;
const h = React.createElement;

const actIcon: Record<string, string> = { login: "logout", grant: "key", approve: "userCheck", reset: "refresh", revoke: "ban", loginfail: "alert" };

export function Dashboard({ go, toast }: { go: (id: string) => void; toast: (msg: string, icon?: string) => void }) {
  const { users, teams } = useAdminData();
  const pending = users.filter((u) => u.status === "pending");
  const active = users.filter((u) => u.status === "active").length;

  const [recent, setRecent] = useState<ApiAudit[]>([]);
  const [loading, setLoading] = useState(true);
  useEffect(() => {
    let alive = true;
    AdminApi.audit({ limit: 8 })
      .then((res) => { if (alive) setRecent(res.rows); })
      .catch((e) => { if (alive) toast(e instanceof ApiError ? e.message : "최근 활동을 불러오지 못했습니다"); })
      .finally(() => { if (alive) setLoading(false); });
    return () => { alive = false; };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const stats: { label: string; num: number; delta: string; onClick?: () => void }[] = [
    { label: "전체 사용자", num: users.length, delta: "등록된 전체 계정" },
    { label: "활성 계정", num: active, delta: "로그인 가능한 계정" },
    { label: "가입 대기", num: pending.length, delta: "클릭하여 승인 처리", onClick: () => go("pending") },
    { label: "팀", num: teams.length, delta: "팀 스페이스 단위" },
  ];

  return h("div", { className: "apage" },
    h(SecHead, { title: "개요", hint: "워크스페이스 사용자 및 접근 현황" }),
    h("div", { className: "stat-grid" },
      stats.map((s, i) => h("div", {
        className: "stat", key: i,
        onClick: s.onClick,
        style: s.onClick ? { cursor: "pointer" } : undefined,
      },
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
                  h("span", { className: "ml-tgt" }, p.name),
                  h("span", { className: "ml-at" }, p.email ?? "—")))))),
      h("div", { className: "panel" },
        h("div", { className: "panel-head" },
          h(Icon, { name: "history" }), "최근 활동",
          h("span", { style: { marginLeft: "auto" } },
            h("button", { className: "btn sm", onClick: () => go("audit") }, "전체 보기"))),
        h("div", { className: "panel-body", style: { paddingTop: 4, paddingBottom: 4 } },
          loading
            ? h("div", { className: "muted", style: { padding: "20px 0", textAlign: "center", color: "var(--text-3)", fontSize: 12.5 } }, "불러오는 중…")
            : recent.length === 0
              ? h(Empty, { icon: "history", title: "기록된 활동이 없습니다" })
              : h("div", { className: "mini-log" },
                  recent.map((r) => h("div", { className: "ml", key: r.id },
                    h("span", { className: "ml-ic" }, h(Icon, { name: actIcon[actType(r.act)] || "dot" })),
                    h("span", { className: "ml-act" }, actLabel(r.act)),
                    h("span", { className: "ml-tgt mono" }, r.who + " · " + (r.target ?? "—")),
                    h("span", { className: "ml-at" }, r.at.replace("T", " ").slice(5, 19))))))))
  );
}
