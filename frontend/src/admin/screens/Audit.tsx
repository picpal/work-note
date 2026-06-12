/* Admin screen 6: Audit log — 실 API(AdminApi.audit) 배선. 필터는 서버 단일 경로(who/act 정확 일치 + from/to 사전순). */
import React from "react";
import { AdminApi, ApiAudit } from "../api";
import { actLabel, actType, KNOWN_ACTS } from "../mappers";
import { ApiError } from "../../api/http";
import { SecHead, Empty, SkeletonTable } from "../common";
import { Icon } from "../../components/Icon";

const { useState, useEffect } = React;
const h = React.createElement;

const LIMIT = 50;

function adminDownload(filename: string, text: string, mime?: string) {
  const blob = new Blob(["﻿" + text], { type: (mime || "text/plain") + ";charset=utf-8" });
  const url = URL.createObjectURL(blob);
  const a = document.createElement("a");
  a.href = url; a.download = filename;
  document.body.appendChild(a); a.click();
  setTimeout(() => { document.body.removeChild(a); URL.revokeObjectURL(url); }, 100);
}

export function Audit({ toast }: { toast: (msg: string, icon?: string) => void }) {
  const [whoInput, setWhoInput] = useState("");   // 입력 중 값
  const [who, setWho] = useState("");             // 적용된 필터(Enter/blur 시 반영)
  const [act, setAct] = useState("");             // "" = 전체
  const [from, setFrom] = useState("");           // YYYY-MM-DD
  const [to, setTo] = useState("");               // YYYY-MM-DD
  const [offset, setOffset] = useState(0);
  const [rows, setRows] = useState<ApiAudit[]>([]);
  const [total, setTotal] = useState(0);
  const [loading, setLoading] = useState(true);

  /** 필터 변경은 offset 0으로 리셋 — React 배칭으로 effect는 한 번만 실행. */
  const applyWho = (v: string) => { if (v !== who) { setWho(v); setOffset(0); } };
  const applyAct = (v: string) => { setAct(v); setOffset(0); };
  const applyFrom = (v: string) => { setFrom(v); setOffset(0); };
  const applyTo = (v: string) => { setTo(v); setOffset(0); };

  useEffect(() => {
    let alive = true;
    setLoading(true);
    // to는 그날 끝까지 포함(at은 ISO 문자열 사전순 비교), from은 YYYY-MM-DD 그대로 충분.
    AdminApi.audit({ who, act, from, to: to ? to + "T23:59:59" : "", limit: LIMIT, offset })
      .then((res) => { if (alive) { setRows(res.rows); setTotal(res.total); } })
      .catch((e) => { if (alive) { setRows([]); setTotal(0); toast(e instanceof ApiError ? e.message : "감사 로그 조회 실패"); } })
      .finally(() => { if (alive) setLoading(false); });
    return () => { alive = false; };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [who, act, from, to, offset]);

  const stamp = () => new Date().toISOString().slice(0, 19).replace("T", " ");
  const fileStamp = () => new Date().toISOString().slice(0, 10);
  // at은 ISO_LOCAL_DATE_TIME(마이크로초 포함 가능) — 초 단위까지만 표시
  const fmtAt = (at: string) => at.replace("T", " ").slice(0, 19);

  const exportCsv = () => {
    const esc = (s: unknown) => '"' + String(s).replace(/"/g, '""') + '"';
    const head = ["일시", "행위자", "행위", "대상", "IP/단말"];
    const lines = [head.map(esc).join(",")].concat(
      rows.map((r) => [fmtAt(r.at), r.who, actLabel(r.act), r.target ?? "—", r.ip].map(esc).join(",")));
    adminDownload("audit-log_" + fileStamp() + ".csv", lines.join("\r\n"), "text/csv");
    toast && toast(rows.length + "건을 CSV로 내보냈습니다", "download");
  };

  const downloadReport = () => {
    const byType: Record<string, number> = {};
    rows.forEach((r) => { const k = actLabel(r.act); byType[k] = (byType[k] || 0) + 1; });
    const fails = rows.filter((r) => actType(r.act) === "loginfail").length;
    const grants = rows.filter((r) => actType(r.act) === "grant" || actType(r.act) === "revoke").length;
    const period = rows.length ? (rows[rows.length - 1].at.slice(0, 10) + " ~ " + rows[0].at.slice(0, 10)) : "—";
    let md = "";
    md += "# WorkNote 감사 리포트\n\n";
    md += "- 생성 일시: " + stamp() + "\n";
    md += "- 대상 기간: " + period + "\n";
    md += "- 수록 이벤트: " + rows.length + "건 (전체 " + total + "건 중 현재 페이지)\n";
    md += "- 권한 변경(부여/회수): " + grants + "건\n";
    md += "- 로그인 실패: " + fails + "건\n\n";
    md += "## 행위 유형별 집계\n\n";
    md += "| 행위 | 건수 |\n| --- | --- |\n";
    Object.keys(byType).forEach((k) => { md += "| " + k + " | " + byType[k] + " |\n"; });
    md += "\n## 로그 (시간 역순)\n\n";
    md += "| 일시 | 행위자 | 행위 | 대상 | IP/단말 |\n| --- | --- | --- | --- | --- |\n";
    rows.forEach((r) => { md += "| " + fmtAt(r.at) + " | " + r.who + " | " + actLabel(r.act) + " | " + (r.target || "—") + " | " + r.ip + " |\n"; });
    md += "\n---\n_본 리포트는 ISMS · PCI-DSS 감사 추적 목적으로 자동 생성되었습니다._\n";
    adminDownload("audit-report_" + fileStamp() + ".md", md, "text/markdown");
    toast && toast("감사 리포트를 내려받았습니다", "check");
  };

  const first = total === 0 ? 0 : offset + 1;
  const last = Math.min(offset + rows.length, total);
  const hasPrev = offset > 0;
  const hasNext = offset + LIMIT < total;

  return h("div", { className: "apage wide" },
    h(SecHead, { title: "감사 로그", hint: "ISMS · PCI-DSS 추적용 · 시간 역순" }),
    h("div", { className: "atoolbar" },
      h("div", { className: "afield" }, h(Icon, { name: "search" }),
        h("input", { placeholder: "행위자(사번) — Enter로 적용", value: whoInput,
          onChange: (e: React.ChangeEvent<HTMLInputElement>) => setWhoInput(e.target.value),
          onKeyDown: (e: React.KeyboardEvent<HTMLInputElement>) => { if (e.nativeEvent.isComposing) return; if (e.key === "Enter") applyWho(whoInput.trim()); },
          onBlur: () => applyWho(whoInput.trim()) })),
      h("select", { className: "aselect", value: act, onChange: (e: React.ChangeEvent<HTMLSelectElement>) => applyAct(e.target.value) },
        h("option", { value: "" }, "전체 행위"),
        KNOWN_ACTS.map((k) => h("option", { key: k, value: k }, actLabel(k)))),
      h("input", { className: "aselect", type: "date", value: from, title: "시작일",
        onChange: (e: React.ChangeEvent<HTMLInputElement>) => applyFrom(e.target.value) }),
      h("input", { className: "aselect", type: "date", value: to, title: "종료일",
        onChange: (e: React.ChangeEvent<HTMLInputElement>) => applyTo(e.target.value) }),
      h("span", { style: { flex: 1 } }),
      h("button", { className: "btn", onClick: downloadReport, disabled: loading || rows.length === 0 }, h(Icon, { name: "fileLines" }), "감사 리포트"),
      h("button", { className: "btn", onClick: exportCsv, disabled: loading || rows.length === 0 }, h(Icon, { name: "download" }), "내보내기")),
    loading
      ? h(SkeletonTable, { cols: 5 })
      : rows.length === 0
        ? h(Empty, { icon: "history", title: "조건에 맞는 로그가 없습니다" })
        : h("div", { className: "table-wrap" },
            h("table", { className: "atable" },
              h("thead", null, h("tr", null,
                h("th", null, "일시"), h("th", null, "행위자"), h("th", null, "행위"),
                h("th", null, "대상"), h("th", null, "IP / 단말"))),
              h("tbody", null,
                rows.map((r) => h("tr", { key: r.id },
                  h("td", { className: "mono muted" }, fmtAt(r.at)),
                  h("td", { className: "mono" }, r.who),
                  h("td", null, h("span", { style: { fontWeight: 550, color: actType(r.act) === "loginfail" ? "#b3261e" : "var(--ink)" } }, actLabel(r.act))),
                  h("td", { className: "muted" }, r.target ?? "—"),
                  h("td", { className: "mono muted" }, r.ip)))))),
    h("div", { className: "atoolbar", style: { marginTop: 12, marginBottom: 0 } },
      h("span", { className: "muted", style: { fontSize: 12.5 } },
        loading ? "불러오는 중…" : total + "건 중 " + first + "–" + last),
      h("span", { style: { flex: 1 } }),
      h("button", { className: "btn sm", disabled: loading || !hasPrev, onClick: () => setOffset(Math.max(0, offset - LIMIT)) }, "이전"),
      h("button", { className: "btn sm", disabled: loading || !hasNext, onClick: () => setOffset(offset + LIMIT) }, "다음"))
  );
}
