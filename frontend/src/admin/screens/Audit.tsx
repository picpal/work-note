/* Admin screen 6: Audit log */
import React from "react";
import { ADMIN_AUDIT } from "../data";
import { SecHead, Empty } from "../common";
import { Icon } from "../../components/Icon";

const { useState } = React;
const h = React.createElement;

function adminDownload(filename: string, text: string, mime?: string) {
  const blob = new Blob(["﻿" + text], { type: (mime || "text/plain") + ";charset=utf-8" });
  const url = URL.createObjectURL(blob);
  const a = document.createElement("a");
  a.href = url; a.download = filename;
  document.body.appendChild(a); a.click();
  setTimeout(() => { document.body.removeChild(a); URL.revokeObjectURL(url); }, 100);
}

export function Audit({ toast }: { toast: (msg: string, icon?: string) => void }) {
  const [who, setWho] = useState("");
  const [type, setType] = useState("all");
  const data = ADMIN_AUDIT;
  const types = [["all", "전체 행위"], ["login", "로그인"], ["grant", "권한 부여"], ["revoke", "권한 회수"], ["approve", "계정 승인"], ["reset", "비번 초기화"], ["deactivate", "계정 비활성화"], ["loginfail", "로그인 실패"]];
  const rows = data.filter((r) => (!who || r.who.toLowerCase().includes(who.toLowerCase())) && (type === "all" || r.actType === type));

  const stamp = () => new Date().toISOString().slice(0, 19).replace("T", " ");
  const fileStamp = () => new Date().toISOString().slice(0, 10);

  const exportCsv = () => {
    const esc = (s: unknown) => '"' + String(s).replace(/"/g, '""') + '"';
    const head = ["일시", "행위자", "행위", "대상", "IP/단말"];
    const lines = [head.map(esc).join(",")].concat(
      rows.map((r) => [r.at, r.who, r.act, r.target, r.ip].map(esc).join(",")));
    adminDownload("audit-log_" + fileStamp() + ".csv", lines.join("\r\n"), "text/csv");
    toast && toast(rows.length + "건을 CSV로 내보냈습니다", "download");
  };

  const downloadReport = () => {
    const byType: Record<string, number> = {};
    data.forEach((r) => { byType[r.act] = (byType[r.act] || 0) + 1; });
    const fails = data.filter((r) => r.actType === "loginfail").length;
    const grants = data.filter((r) => r.actType === "grant" || r.actType === "revoke").length;
    const period = data.length ? (data[data.length - 1].at.slice(0, 10) + " ~ " + data[0].at.slice(0, 10)) : "—";
    let md = "";
    md += "# WorkNote 감사 리포트\n\n";
    md += "- 생성 일시: " + stamp() + "\n";
    md += "- 대상 기간: " + period + "\n";
    md += "- 총 이벤트: " + data.length + "건\n";
    md += "- 권한 변경(부여/회수): " + grants + "건\n";
    md += "- 로그인 실패: " + fails + "건\n\n";
    md += "## 행위 유형별 집계\n\n";
    md += "| 행위 | 건수 |\n| --- | --- |\n";
    Object.keys(byType).forEach((k) => { md += "| " + k + " | " + byType[k] + " |\n"; });
    md += "\n## 전체 로그 (시간 역순)\n\n";
    md += "| 일시 | 행위자 | 행위 | 대상 | IP/단말 |\n| --- | --- | --- | --- | --- |\n";
    data.forEach((r) => { md += "| " + r.at + " | " + r.who + " | " + r.act + " | " + (r.target || "—") + " | " + r.ip + " |\n"; });
    md += "\n---\n_본 리포트는 ISMS · PCI-DSS 감사 추적 목적으로 자동 생성되었습니다._\n";
    adminDownload("audit-report_" + fileStamp() + ".md", md, "text/markdown");
    toast && toast("감사 리포트를 내려받았습니다", "check");
  };

  return h("div", { className: "apage wide" },
    h(SecHead, { title: "감사 로그", hint: "ISMS · PCI-DSS 추적용 · 시간 역순" }),
    h("div", { className: "atoolbar" },
      h("div", { className: "afield" }, h(Icon, { name: "search" }),
        h("input", { placeholder: "행위자(사번) 검색", value: who, onChange: (e: React.ChangeEvent<HTMLInputElement>) => setWho(e.target.value) })),
      h("select", { className: "aselect", value: type, onChange: (e: React.ChangeEvent<HTMLSelectElement>) => setType(e.target.value) },
        types.map((t) => h("option", { key: t[0], value: t[0] }, t[1]))),
      h("span", { style: { flex: 1 } }),
      h("button", { className: "btn", onClick: downloadReport }, h(Icon, { name: "fileLines" }), "감사 리포트"),
      h("button", { className: "btn", onClick: exportCsv }, h(Icon, { name: "download" }), "내보내기")),
    rows.length === 0
      ? h(Empty, { icon: "history", title: "조건에 맞는 로그가 없습니다" })
      : h("div", { className: "table-wrap" },
          h("table", { className: "atable" },
            h("thead", null, h("tr", null,
              h("th", null, "일시"), h("th", null, "행위자"), h("th", null, "행위"),
              h("th", null, "대상"), h("th", null, "IP / 단말"))),
            h("tbody", null,
              rows.map((r, i) => h("tr", { key: i },
                h("td", { className: "mono muted" }, r.at),
                h("td", { className: "mono" }, r.who),
                h("td", null, h("span", { style: { fontWeight: 550, color: r.actType === "loginfail" ? "#b3261e" : "var(--ink)" } }, r.act)),
                h("td", { className: "muted" }, r.target),
                h("td", { className: "mono muted" }, r.ip))))))
  );
}
