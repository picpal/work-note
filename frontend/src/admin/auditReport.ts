/* 월간 감사 리포트 빌더 — 사내 보안/개인정보 점검표 5분류(로그·접근·관리자·계정·조회/다운로드)를 마크다운으로 생성.
   순수 함수: 입력(그 달 audit 전건 + 사용자 명부 + 역할 + 생성일시)만으로 결정 → vitest 단위 검증.
   "조회 건수"는 note.view(프런트 조회 핑)로 계측, "다운로드"는 attachment.download + note.export. */
import type { ApiAudit, ApiUser, ApiRole } from "./api";
import { actLabel } from "./mappers";

const pad2 = (n: number) => String(n).padStart(2, "0");

/** 그 달의 마지막 일자(1~12월). new Date(year, month, 0) = 해당 월의 말일. */
export function lastDayOf(year: number, month: number): number {
  return new Date(year, month, 0).getDate();
}

/** 월 경계(ISO_LOCAL_DATE_TIME 사전순 비교용 — 백엔드 audit from/to 계약).
    to는 나노초 변형까지 포함하도록 .999999999 부여(말초 마이크로초 행 누락 방지). */
export function monthBounds(year: number, month: number): { from: string; to: string } {
  return {
    from: `${year}-${pad2(month)}-01T00:00:00`,
    to: `${year}-${pad2(month)}-${pad2(lastDayOf(year, month))}T23:59:59.999999999`,
  };
}

/** 업무시간(평일 08:00–18:59) 외 여부 — 주말 또는 08시 이전·19시 이후. at=ISO_LOCAL_DATE_TIME. */
export function isOffHours(at: string): boolean {
  const y = +at.slice(0, 4), mo = +at.slice(5, 7), d = +at.slice(8, 10);
  const hour = +at.slice(11, 13);
  const dow = new Date(y, mo - 1, d).getDay(); // 0=일, 6=토
  return dow === 0 || dow === 6 || hour < 8 || hour >= 19;
}

/** caps에 admin.* 가 하나라도 있는 역할 = 관리자 역할. */
export function adminRoleIds(roles: ApiRole[]): Set<string> {
  return new Set(roles.filter((r) => r.caps.some((c) => c.startsWith("admin."))).map((r) => r.id));
}

/** 관리자 작업으로 보는 act — 명백한 관리 행위(계정·역할·팀·ACL·공개·스페이스·설정·퍼지·관리자 2FA 초기화). */
const ADMIN_ACTION_PREFIXES = ["user.", "role.", "team.", "acl.", "public.", "space.", "settings."];
export function isAdminAction(act: string): boolean {
  return ADMIN_ACTION_PREFIXES.some((p) => act.startsWith(p)) || act === "2fa.admin.reset" || act === "node.purge";
}

const DOWNLOAD_ACTS = new Set(["attachment.download", "note.export"]);
const ACCT_LABEL: Record<string, string> = {
  "user.create": "신규 생성", "user.approve": "계정 승인", "user.update": "정보/권한 변경", "user.reset": "비밀번호 초기화",
};

export interface ReportInput {
  year: number;
  month: number;            // 1~12
  rows: ApiAudit[];         // 그 달 audit 전건(시간 무관 — 빌더가 집계)
  users: ApiUser[];         // 사용자 명부(미접속 일수·관리자 수)
  roles: ApiRole[];         // 관리자 판별(caps)
  generatedAt: string;      // "YYYY-MM-DD HH:MM:SS" — 호출측 주입(테스트 결정성)
}

// ---- 내부 집계 헬퍼 ----
const cell = (v: unknown) => String(v ?? "—").replace(/\|/g, "\\|").replace(/\r?\n/g, " ");
const countByWho = (rs: ApiAudit[]) => {
  const m = new Map<string, number>();
  for (const r of rs) m.set(r.who, (m.get(r.who) || 0) + 1);
  return m;
};
const lastByWho = (rs: ApiAudit[]) => {
  const m = new Map<string, ApiAudit>();
  for (const r of rs) { const p = m.get(r.who); if (!p || r.at > p.at) m.set(r.who, r); }
  return m;
};
const topN = (m: Map<string, number>, n: number) =>
  [...m.entries()].sort((a, b) => b[1] - a[1] || a[0].localeCompare(b[0])).slice(0, n);
const fmtAt = (at: string) => at.replace("T", " ").slice(0, 19);
const daysBetween = (fromDay: string, toDay: string) =>
  Math.floor((Date.parse(toDay + "T00:00:00") - Date.parse(fromDay + "T00:00:00")) / 86400000);

/** 월간 감사 리포트 마크다운 생성. */
export function buildAuditReport(i: ReportInput): string {
  const { year, month, rows, users, roles, generatedAt } = i;
  const ym = `${year}-${pad2(month)}`;
  const refDay = generatedAt.slice(0, 10);

  const adminIds = adminRoleIds(roles);
  const adminUsers = users.filter((u) => adminIds.has(u.roleId));
  const adminEmps = new Set(adminUsers.map((u) => u.emp));
  const nameOf = new Map(users.map((u) => [u.emp, u.name] as const));
  const who = (emp: string) => { const n = nameOf.get(emp); return n ? `${n}(${emp})` : emp; };

  const logins = rows.filter((r) => r.act === "login.success");
  const loginFails = rows.filter((r) => r.act === "login.fail");
  const logouts = rows.filter((r) => r.act === "logout");
  const adminActions = rows.filter((r) => isAdminAction(r.act));
  const acctChanges = rows.filter((r) => r.act in ACCT_LABEL);
  const views = rows.filter((r) => r.act === "note.view");
  const downloads = rows.filter((r) => DOWNLOAD_ACTS.has(r.act));
  const shareViews = rows.filter((r) => r.act === "share.view");

  const L: string[] = [];
  const p = (s = "") => L.push(s);

  p(`# WorkNote 월간 감사 리포트 — ${ym}`);
  p();
  p(`- 생성 일시: ${generatedAt}`);
  p(`- 대상 기간: ${ym}-01 ~ ${ym}-${pad2(lastDayOf(year, month))}`);
  p(`- 총 감사 이벤트: ${rows.length}건`);
  p(`- 등록 사용자: ${users.length}명 (관리자 ${adminUsers.length}명)`);
  p();

  // ---- 1. 로그 관리 ----
  p(`## 1. 로그 관리`);
  p();
  p(`- 로그인/접근 로그 기록: ${rows.length > 0 ? "정상 — 접속 기록 수록됨" : "해당 월 기록 없음"}`);
  p(`- 로그인 성공 ${logins.length}건 · 로그인 실패 ${loginFails.length}건 · 로그아웃 ${logouts.length}건`);
  const loginUsers = new Set(logins.map((r) => r.who));
  p(`- 로그인(접속) 사용자: 총 ${loginUsers.size}명`);
  p();
  if (logins.length || loginFails.length) {
    const fails = countByWho(loginFails);
    p(`| 사용자(사번) | 로그인 | 로그인 실패 |`);
    p(`| --- | ---: | ---: |`);
    for (const [emp, n] of topN(countByWho(logins.length ? logins : loginFails), 1000))
      p(`| ${cell(who(emp))} | ${n} | ${fails.get(emp) || 0} |`);
    // 실패만 있고 성공 없는 사용자도 표기
    for (const [emp, n] of fails) if (!loginUsers.has(emp)) p(`| ${cell(who(emp))} | 0 | ${n} |`);
    p();
  }

  // ---- 2. 접근 관리 ----
  p(`## 2. 접근 관리`);
  p();
  p(`- 사용자별 마지막 접속 일시·IP (해당 월 로그인 기준)`);
  p();
  const lastLogin = lastByWho(logins);
  if (lastLogin.size) {
    p(`| 사용자(사번) | 마지막 접속 일시 | 접속 IP |`);
    p(`| --- | --- | --- |`);
    for (const [, r] of [...lastLogin].sort((a, b) => (a[1].at > b[1].at ? -1 : 1)))
      p(`| ${cell(who(r.who))} | ${fmtAt(r.at)} | ${cell(r.ip)} |`);
  } else {
    p(`_해당 월 로그인 기록 없음._`);
  }
  p();
  p(`- 관리자 권한 로그인 내역`);
  p();
  const adminLogins = logins.filter((r) => adminEmps.has(r.who));
  if (adminLogins.length) {
    const cnt = countByWho(adminLogins);
    const last = lastByWho(adminLogins);
    p(`| 관리자(사번) | 로그인 수 | 마지막 IP |`);
    p(`| --- | ---: | --- |`);
    for (const [emp, n] of topN(cnt, 1000)) p(`| ${cell(who(emp))} | ${n} | ${cell(last.get(emp)?.ip)} |`);
  } else {
    p(`_해당 월 관리자 로그인 기록 없음._`);
  }
  p();

  // ---- 3. 관리자 로그 관리 ----
  p(`## 3. 관리자 로그 관리`);
  p();
  p(`- 관리자 작업 수행: ${adminActions.length}건`);
  p();
  if (adminActions.length) {
    p(`| 일시 | 관리자(사번) | 작업 | 대상 |`);
    p(`| --- | --- | --- | --- |`);
    for (const r of [...adminActions].sort((a, b) => (a.at > b.at ? -1 : 1)))
      p(`| ${fmtAt(r.at)} | ${cell(who(r.who))} | ${cell(actLabel(r.act))} | ${cell(r.target)} |`);
    p();
  }
  p(`- 관리자 계정 수: **${adminUsers.length}개**`);
  p(adminUsers.length >= 4
    ? `  - ⚠️ 관리자 계정 4개 이상 — 소명 및 부서장 승인 이력이 필요합니다.`
    : `  - 정상 (4개 미만 — 별도 승인 이력 불요).`);
  p();
  if (adminUsers.length) {
    const roleName = new Map(roles.map((r) => [r.id, r.name] as const));
    p(`| 관리자(사번) | 역할 | 상태 |`);
    p(`| --- | --- | --- |`);
    for (const u of adminUsers) p(`| ${cell(who(u.emp))} | ${cell(roleName.get(u.roleId) || u.roleId)} | ${cell(u.status)} |`);
    p();
  }

  // ---- 4. 계정 관리 ----
  p(`## 4. 계정 관리`);
  p();
  p(`- 당월 계정 변동: ${acctChanges.length}건 (신규 생성·승인·정보/권한 변경·비밀번호 초기화)`);
  p(`  - ※ 계정 정지/삭제는 정보 변경(user.update)에 포함되어 별도 act로 구분되지 않습니다.`);
  p();
  if (acctChanges.length) {
    p(`| 일시 | 구분 | 대상(사번) | 처리자(사번) |`);
    p(`| --- | --- | --- | --- |`);
    for (const r of [...acctChanges].sort((a, b) => (a.at > b.at ? -1 : 1)))
      p(`| ${fmtAt(r.at)} | ${cell(ACCT_LABEL[r.act])} | ${cell(r.target)} | ${cell(who(r.who))} |`);
    p();
  }
  p(`- 사용자 계정·미접속 현황 (명부 기준, ${refDay} 기준 미접속 일수)`);
  p();
  p(`| 사용자(사번) | 상태 | 마지막 로그인 | 미접속 일수 |`);
  p(`| --- | --- | --- | ---: |`);
  for (const u of [...users].sort((a, b) => a.emp.localeCompare(b.emp))) {
    const day = u.lastLogin ? u.lastLogin.slice(0, 10) : null;
    const idle = day ? daysBetween(day, refDay) : "—";
    p(`| ${cell(who(u.emp))} | ${cell(u.status)} | ${cell(day)} | ${idle} |`);
  }
  p();

  // ---- 5. 조회/다운로드 이력 ----
  p(`## 5. 조회/다운로드 이력`);
  p();
  const viewBy = countByWho(views);
  const dlBy = countByWho(downloads);
  const denom = Math.max(users.length, 1);
  p(`- 노트 조회(view): 총 ${views.length}건 · 조회 사용자 ${viewBy.size}명 · 사용자당 평균 ${(views.length / denom).toFixed(1)}건`);
  p(`- 다운로드(첨부+내보내기): 총 ${downloads.length}건 · 다운로드 사용자 ${dlBy.size}명 · 사용자당 평균 ${(downloads.length / denom).toFixed(1)}건`);
  p(`- 공유 링크 열람: ${shareViews.length}건`);
  p();
  const top3 = (m: Map<string, number>, label: string) => {
    p(`- 최다 ${label} 상위 3`);
    p();
    if (!m.size) { p(`_해당 월 ${label} 기록 없음._`); p(); return; }
    p(`| 사용자(사번) | ${label}수 |`);
    p(`| --- | ---: |`);
    for (const [emp, n] of topN(m, 3)) p(`| ${cell(who(emp))} | ${n} |`);
    p();
  };
  top3(viewBy, "조회");
  top3(dlBy, "다운로드");

  // 업무시간 외
  const off = [...views, ...downloads].filter((r) => isOffHours(r.at)).sort((a, b) => (a.at > b.at ? -1 : 1));
  p(`- 업무시간 외(평일 08–19시 외·주말) 조회/다운로드: ${off.length}건`);
  p();
  if (off.length) {
    p(`| 일시 | 사용자(사번) | 행위 | 대상 |`);
    p(`| --- | --- | --- | --- |`);
    for (const r of off) p(`| ${fmtAt(r.at)} | ${cell(who(r.who))} | ${cell(actLabel(r.act))} | ${cell(r.target)} |`);
    p();
  }

  // 과다 조회/다운로드 점검 (상위 1명)
  const topView = topN(viewBy, 1)[0];
  const topDl = topN(dlBy, 1)[0];
  p(`- 과다 조회/다운로드 점검 (상위 1명 별도 검토 권고)`);
  p(`  - 최다 조회: ${topView ? `${who(topView[0])} — ${topView[1]}건` : "없음"}`);
  p(`  - 최다 다운로드: ${topDl ? `${who(topDl[0])} — ${topDl[1]}건` : "없음"}`);
  p();

  p(`---`);
  p(`_본 리포트는 개인정보보호·접속기록 점검 목적으로 자동 생성되었습니다. 노트 조회(view)는 조회 핑 도입 이후 누적되며, 도입 전 기간은 0건으로 표기될 수 있습니다._`);

  return L.join("\n") + "\n";
}

// ---- PDF용 HTML 변환 ----
// 리포트 마크다운은 이 모듈이 직접 생성하는 한정된 부분집합(#/## 헤딩·- 불릿·| 표·**굵게**·_본문_·---)만
// 쓰므로 범용 마크다운 엔진을 끌어오지 않고 그 부분집합만 변환한다(번들 경량·동작 예측 가능).
const PH = " "; // 셀 내 이스케이프된 \| 임시 치환자

function splitTableRow(line: string): string[] {
  return line.replace(/\\\|/g, PH).trim().replace(/^\||\|$/g, "").split("|")
    .map((c) => c.split(PH).join("|").trim());
}
// 구분선(| --- | ---: |)에서 열 정렬 추출: 양끝 콜론=center, 우측 콜론=right, 그 외 left.
function alignsFromSeparator(sep: string): string[] {
  return splitTableRow(sep).map((c) => {
    const l = c.startsWith(":"), r = c.endsWith(":");
    return l && r ? "c" : r ? "r" : "";
  });
}

/** 리포트 마크다운(이 모듈 출력) → HTML 본문 문자열. */
export function reportMarkdownToHtml(md: string): string {
  const esc = (s: string) => s.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;");
  const inline = (s: string) => esc(s).replace(/\*\*(.+?)\*\*/g, "<strong>$1</strong>");
  const lines = md.split("\n");
  const out: string[] = [];
  let i = 0;
  while (i < lines.length) {
    const line = lines[i];
    if (line.startsWith("# ")) { out.push(`<h1>${inline(line.slice(2))}</h1>`); i++; continue; }
    if (line.startsWith("## ")) { out.push(`<h2>${inline(line.slice(3))}</h2>`); i++; continue; }
    if (/^---\s*$/.test(line)) { out.push("<hr>"); i++; continue; }
    if (line.trim() === "") { i++; continue; }
    // 표 블록: | 로 시작 + 다음 줄이 구분선
    if (line.startsWith("|") && i + 1 < lines.length && /^\|[\s:|-]+\|?\s*$/.test(lines[i + 1])) {
      const header = splitTableRow(line);
      const aligns = alignsFromSeparator(lines[i + 1]);
      const cls = (k: number) => (aligns[k] ? ` class="${aligns[k]}"` : "");
      i += 2;
      const rows: string[][] = [];
      while (i < lines.length && lines[i].startsWith("|")) { rows.push(splitTableRow(lines[i])); i++; }
      const thead = `<tr>${header.map((c, k) => `<th${cls(k)}>${inline(c)}</th>`).join("")}</tr>`;
      const tbody = rows.map((r) => `<tr>${r.map((c, k) => `<td${cls(k)}>${inline(c)}</td>`).join("")}</tr>`).join("");
      out.push(`<table><thead>${thead}</thead><tbody>${tbody}</tbody></table>`);
      continue;
    }
    if (/^\s*-\s/.test(line)) {
      const items: string[] = [];
      while (i < lines.length && /^\s*-\s/.test(lines[i])) {
        const sub = /^\s+-\s/.test(lines[i]);
        items.push(`<li${sub ? ' class="sub"' : ""}>${inline(lines[i].replace(/^\s*-\s/, ""))}</li>`);
        i++;
      }
      out.push(`<ul>${items.join("")}</ul>`);
      continue;
    }
    if (/^_.*_$/.test(line)) { out.push(`<p class="muted">${inline(line.replace(/^_/, "").replace(/_$/, ""))}</p>`); i++; continue; }
    out.push(`<p>${inline(line)}</p>`); i++;
  }
  return out.join("\n");
}

const REPORT_PRINT_CSS = `
*{box-sizing:border-box}
body{font-family:'Pretendard',system-ui,-apple-system,'Apple SD Gothic Neo',sans-serif;color:#1a1a1a;margin:28px;font-size:13px;line-height:1.6}
h1{font-size:20px;margin:0 0 4px}
h2{font-size:15px;margin:22px 0 8px;padding-bottom:4px;border-bottom:1px solid #ddd}
ul{margin:6px 0;padding-left:18px}
li{margin:2px 0}
li.sub{list-style:none;margin-left:6px;color:#555}
table{border-collapse:collapse;width:100%;margin:8px 0 14px;font-size:12px}
th,td{border:1px solid #ccc;padding:5px 8px;text-align:left;vertical-align:top}
th{background:#f3f3f3;font-weight:600}
td.r,th.r{text-align:right}
td.c,th.c{text-align:center}
.muted{color:#888;font-size:11.5px}
hr{border:none;border-top:1px solid #ddd;margin:18px 0}
strong{font-weight:600}
@page{margin:14mm}
@media print{body{margin:0}table{page-break-inside:auto}tr{page-break-inside:avoid}}
`;

/** 인쇄(PDF 저장)용 완성 HTML 문서 — 새 창에 써넣고 print() 호출. */
export function buildReportHtmlDoc(title: string, md: string): string {
  return `<!doctype html><html lang="ko"><head><meta charset="utf-8"><title>${title}</title><style>${REPORT_PRINT_CSS}</style></head><body>${reportMarkdownToHtml(md)}</body></html>`;
}
