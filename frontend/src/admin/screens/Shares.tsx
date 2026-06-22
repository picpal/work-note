/* Admin screen: 공유 링크 — 활성 링크 일괄 조회·취소(스펙 §6). token 원문은 표시하지 않는다(통제·취소 목적, 재배포 목적 아님). */
import React from "react";
import { AdminApi, ApiShare } from "../api";
import { ApiError } from "../../api/http";
import { SecHead, Empty, SkeletonTable } from "../common";

const { useState, useEffect, useCallback } = React;
const h = React.createElement;

export function Shares({ toast }: { toast: (msg: string, icon?: string) => void }) {
  const [rows, setRows] = useState<ApiShare[] | null>(null);
  const [busyId, setBusyId] = useState<string | null>(null);

  const reload = useCallback(async () => {
    try {
      setRows(await AdminApi.shares());
    } catch (e) {
      toast(e instanceof ApiError ? e.message : "공유 링크를 불러오지 못했습니다");
      setRows([]);
    }
  }, [toast]);
  useEffect(() => { void reload(); }, [reload]);

  // at은 ISO_LOCAL_DATE_TIME(마이크로초 포함 가능) — 초 단위까지만 표시 (Audit.tsx fmtAt 관례)
  const fmtAt = (at: string) => at.replace("T", " ").slice(0, 19);

  const revoke = async (r: ApiShare) => {
    if (busyId) return;
    if (!confirm(`'${r.nodeName}' 공유 링크를 취소할까요? 받은 사람은 더 이상 열 수 없습니다.`)) return;
    setBusyId(r.id);
    try {
      await AdminApi.revokeShare(r.id);
      toast("'" + r.nodeName + "' 공유 링크를 취소했습니다", "check");
      await reload();
    } catch (e) {
      toast(e instanceof ApiError ? e.message : "공유 링크 취소 실패");
    } finally {
      setBusyId(null);
    }
  };

  return h("div", { className: "apage wide" },
    h(SecHead, { title: "공유 링크", hint: "활성 링크 일괄 조회·취소 — deny를 넘는 유일한 read 예외" }),
    rows === null
      ? h(SkeletonTable, { cols: 6 })
      : rows.length === 0
        ? h(Empty, { icon: "link", title: "활성 공유 링크가 없습니다" })
        : h("div", { className: "table-wrap" },
            h("table", { className: "atable center-all" },
              h("thead", null, h("tr", null,
                h("th", null, "노트"), h("th", null, "생성자"), h("th", null, "생성일"),
                h("th", null, "만료일"), h("th", null, "열람"), h("th", null, "대상"),
                h("th", { className: "right" }, "작업"))),
              h("tbody", null,
                rows.map((r) => h("tr", { key: r.id },
                  h("td", null,
                    h("b", { style: { color: "var(--ink)", fontWeight: 600 } }, r.nodeName),
                    r.suspended && h("span", { className: "badge inactive", style: { marginLeft: 8 } }, "휴지통")),
                  h("td", { className: "mono" }, r.createdBy),
                  h("td", { className: "mono muted" }, fmtAt(r.createdAt)),
                  h("td", { className: "mono muted" }, fmtAt(r.expiresAt)),
                  h("td", { className: "mono" }, r.viewCount + " / " + (r.maxViews ?? "∞")),
                  h("td", { className: "muted" }, r.pinEmps?.join(", ") ?? "전 직원"),
                  h("td", { className: "right" },
                    h("button", { className: "lact danger", disabled: busyId === r.id, onClick: () => void revoke(r) }, "취소"))))))));
}
