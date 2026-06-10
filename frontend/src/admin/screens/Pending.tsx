/* Admin screen 2: Pending approvals */
import React from "react";
import { ADMIN_PENDING, PendingRow } from "../data";
import { SecHead, Empty, Modal, StatusBadge } from "../common";
import { Icon } from "../../components/Icon";

const { useState } = React;
const h = React.createElement;

export function Pending({ toast }: { toast: (msg: string, icon?: string) => void }) {
  const [rows, setRows] = useState(ADMIN_PENDING.map((p) => ({ ...p })));
  const [confirm, setConfirm] = useState<{ row: PendingRow; type: string } | null>(null);
  const act = (row: PendingRow, type: string) => setConfirm({ row, type });
  const apply = () => {
    const { row, type } = confirm!;
    setRows((rs) => rs.filter((r) => r.id !== row.id));
    toast(type === "approve" ? row.emp + " 승인됨 (방문자로 활성화)" : row.emp + " 반려됨", type === "approve" ? "userCheck" : "ban");
    setConfirm(null);
  };

  return h("div", { className: "apage" },
    h(SecHead, { title: "가입 승인 대기", hint: "폐쇄망 정책상 가입은 관리자 승인 후 활성화됩니다" }),
    h("div", { className: "panel", style: { marginBottom: 14, background: "var(--bg-sunken)" } },
      h("div", { className: "panel-body", style: { display: "flex", gap: 10, alignItems: "center", padding: "11px 16px" } },
        h("span", { style: { color: "var(--text-3)", display: "grid", placeItems: "center" } }, h(Icon, { name: "info" })),
        h("span", { style: { fontSize: 12.5, color: "var(--text-2)" } },
          "승인 시 계정은 ", h("b", { style: { color: "var(--ink)" } }, "기본 역할(방문자)"), "로 활성화됩니다. 방문자는 공개 노트만 열람할 수 있으며, 추가 권한은 ",
          h("b", { style: { color: "var(--ink)" } }, "권한 관리"), "에서 부여합니다."))),
    rows.length === 0
      ? h(Empty, { icon: "userCheck", title: "대기 중인 가입 신청이 없습니다", desc: "새 신청이 들어오면 이곳에 표시됩니다." })
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
                h("td", null, h(StatusBadge, { status: r.status })),
                h("td", { className: "right" },
                  h("div", { className: "actions" },
                    h("button", { className: "btn sm primary", onClick: () => act(r, "approve") }, "승인"),
                    h("button", { className: "btn sm danger", onClick: () => act(r, "reject") }, "반려")))))))),
    confirm && h(Modal, {
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
