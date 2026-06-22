/* Admin screen 6: Audit log — 실 API(AdminApi.audit) 배선. 필터는 서버 단일 경로(who/act 정확 일치 + from/to 사전순). */
import React from "react";
import { AdminApi, ApiAudit } from "../api";
import { buildAuditReport, buildReportHtmlDoc, monthBounds } from "../auditReport";
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

  // 월간 리포트 모달 — 년/월 선택 후 그 달 전건을 집계해 .md 생성.
  const nowY = new Date().getFullYear();
  const nowM = new Date().getMonth() + 1;
  const [reportOpen, setReportOpen] = useState(false);
  const [ry, setRy] = useState(nowY);
  const [rm, setRm] = useState(nowM);
  const [reportBusy, setReportBusy] = useState(false);
  const yearOpts = Array.from({ length: 5 }, (_, k) => nowY - k);
  const MONTHS = Array.from({ length: 12 }, (_, k) => k + 1);

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

  // 리포트 생성 일시 — 감사 at(서버 LocalDateTime, KST)과 시간대 일치를 위해 로컬 시간으로 표기(toISOString=UTC 회피).
  const stamp = () => {
    const d = new Date(), p = (n: number) => String(n).padStart(2, "0");
    return d.getFullYear() + "-" + p(d.getMonth() + 1) + "-" + p(d.getDate()) + " " + p(d.getHours()) + ":" + p(d.getMinutes()) + ":" + p(d.getSeconds());
  };
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

  /** 선택 월(1일~말일) 전건을 페이지네이션으로 수집 + 명부/역할을 받아 5분류 마크다운 생성. */
  const buildMonthReport = async (): Promise<{ md: string; mm: string; count: number }> => {
    const { from, to } = monthBounds(ry, rm);
    const all: ApiAudit[] = [];
    let off = 0, totalN = Infinity;
    while (all.length < totalN) {
      const res = await AdminApi.audit({ who: "", act: "", from, to, limit: 200, offset: off });
      totalN = res.total;
      if (res.rows.length === 0) break;
      all.push(...res.rows);
      off += res.rows.length;
      if (off > 100000) break;   // 폭주 안전장치
    }
    const [us, rs] = await Promise.all([AdminApi.users(), AdminApi.roles()]);
    const md = buildAuditReport({ year: ry, month: rm, rows: all, users: us, roles: rs, generatedAt: stamp() });
    return { md, mm: String(rm).padStart(2, "0"), count: all.length };
  };

  /** 월간 감사 리포트 생성 — md=파일 다운로드, pdf=인쇄 창(브라우저 'PDF로 저장')으로 노트 내보내기와 동일 패턴. */
  const generateReport = async (format: "md" | "pdf") => {
    if (reportBusy) return;
    setReportBusy(true);
    try {
      const { md, mm, count } = await buildMonthReport();
      if (format === "md") {
        adminDownload("audit-report_" + ry + "-" + mm + ".md", md, "text/markdown");
        toast(ry + "-" + mm + " 감사 리포트를 내려받았습니다 (" + count + "건)", "check");
      } else {
        const w = window.open("", "_blank");
        if (!w) { toast("팝업이 차단되어 PDF를 열 수 없습니다 — 팝업을 허용해주세요"); return; }
        w.document.write(buildReportHtmlDoc("WorkNote 감사 리포트 " + ry + "-" + mm, md));
        w.document.close();
        w.focus();
        setTimeout(() => { try { w.print(); } catch (e) {} }, 350);
        toast("인쇄 대화상자에서 'PDF로 저장'을 선택하세요", "pdf");
      }
      setReportOpen(false);
    } catch (e) {
      toast(e instanceof ApiError ? e.message : "리포트 생성 실패");
    } finally {
      setReportBusy(false);
    }
  };

  const first = total === 0 ? 0 : offset + 1;
  const last = Math.min(offset + rows.length, total);
  const hasPrev = offset > 0;
  const hasNext = offset + LIMIT < total;

  return h("div", { className: "apage wide" },
    h(SecHead, { title: "감사 로그", hint: "ISMS · PCI-DSS 추적용 · 시간 역순",
      right: h(React.Fragment, null,
        h("button", { className: "btn", onClick: () => setReportOpen(true), title: "월을 선택해 월간 감사 리포트 생성" }, h(Icon, { name: "fileLines" }), "감사 리포트"),
        h("button", { className: "btn", onClick: exportCsv, disabled: loading || rows.length === 0 }, h(Icon, { name: "download" }), "내보내기")) }),
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
        onChange: (e: React.ChangeEvent<HTMLInputElement>) => applyTo(e.target.value) })),
    loading
      ? h(SkeletonTable, { cols: 5 })
      : rows.length === 0
        ? h(Empty, { icon: "history", title: "조건에 맞는 로그가 없습니다" })
        : h("div", { className: "table-wrap" },
            h("table", { className: "atable center-all" },
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
      h("button", { className: "btn sm", disabled: loading || !hasNext, onClick: () => setOffset(offset + LIMIT) }, "다음")),
    reportOpen && h("div", { className: "modal-ov", onMouseDown: () => { if (!reportBusy) setReportOpen(false); } },
      h("div", { className: "modal", onMouseDown: (e: React.MouseEvent) => e.stopPropagation() },
        h("div", { className: "modal-head" },
          h("div", { className: "micon" }, h(Icon, { name: "fileLines" })),
          h("h3", null, "월간 감사 리포트")),
        h("div", { className: "modal-body" },
          h("p", { className: "muted", style: { marginTop: 0, marginBottom: 12, fontSize: 13, lineHeight: 1.6 } },
            "리포트를 생성할 연도와 월을 선택하세요. 선택한 달(1일~말일)의 접속·조회·다운로드·계정 기록을 5개 항목으로 집계합니다."),
          h("div", { style: { display: "flex", gap: 8 } },
            h("select", { className: "aselect", style: { flex: 1 }, value: ry, disabled: reportBusy,
              onChange: (e: React.ChangeEvent<HTMLSelectElement>) => setRy(+e.target.value) },
              yearOpts.map((y) => h("option", { key: y, value: y }, y + "년"))),
            h("select", { className: "aselect", style: { flex: 1 }, value: rm, disabled: reportBusy,
              onChange: (e: React.ChangeEvent<HTMLSelectElement>) => setRm(+e.target.value) },
              MONTHS.map((m) => h("option", { key: m, value: m }, m + "월"))))),
        h("div", { className: "modal-foot" },
          h("button", { className: "btn", disabled: reportBusy, onClick: () => setReportOpen(false) }, "취소"),
          h("button", { className: "btn", disabled: reportBusy, onClick: () => void generateReport("md") },
            h(Icon, { name: "markdown" }), "Markdown"),
          h("button", { className: "btn primary", disabled: reportBusy, onClick: () => void generateReport("pdf") },
            h(Icon, { name: "pdf" }), reportBusy ? "생성 중…" : "PDF"))))
  );
}
