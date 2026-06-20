import { describe, it, expect } from "vitest";
import {
  lastDayOf, monthBounds, isOffHours, adminRoleIds, isAdminAction, buildAuditReport,
  reportMarkdownToHtml, buildReportHtmlDoc,
} from "./auditReport";
import type { ApiAudit, ApiUser, ApiRole } from "./api";

const audit = (over: Partial<ApiAudit>): ApiAudit =>
  ({ id: 1, at: "2026-06-01T00:00:00", who: "E1001", act: "login.success", target: null, ip: "10.0.0.1", ...over });
const user = (over: Partial<ApiUser>): ApiUser =>
  ({ id: "u", emp: "E1001", email: null, name: "이름", roleId: "r-op", status: "active", lastLogin: null, totpEnabled: false, ...over });
const role = (over: Partial<ApiRole>): ApiRole =>
  ({ id: "r-op", name: "운영자", system: false, caps: ["res.read"], userCount: 1, ...over });

describe("월 경계", () => {
  it("lastDayOf — 말일", () => {
    expect(lastDayOf(2026, 6)).toBe(30);
    expect(lastDayOf(2026, 2)).toBe(28);
    expect(lastDayOf(2024, 2)).toBe(29); // 윤년
  });
  it("monthBounds — from/to ISO (to는 .999999999)", () => {
    expect(monthBounds(2026, 6)).toEqual({
      from: "2026-06-01T00:00:00",
      to: "2026-06-30T23:59:59.999999999",
    });
    expect(monthBounds(2026, 12).from).toBe("2026-12-01T00:00:00");
  });
});

describe("isOffHours — 업무 08–19시·평일", () => {
  it("평일 업무시간 내 → false", () => {
    expect(isOffHours("2026-06-19T10:00:00")).toBe(false); // 금 10시
    expect(isOffHours("2026-06-19T18:59:00")).toBe(false); // 금 18:59
  });
  it("평일 업무시간 외 → true", () => {
    expect(isOffHours("2026-06-19T07:30:00")).toBe(true); // 금 07:30
    expect(isOffHours("2026-06-19T19:00:00")).toBe(true); // 금 19시
  });
  it("주말은 시간 무관 true", () => {
    expect(isOffHours("2026-06-20T10:00:00")).toBe(true); // 토
    expect(isOffHours("2026-06-21T14:00:00")).toBe(true); // 일
  });
});

describe("관리자 판별", () => {
  it("adminRoleIds — caps에 admin.* 있는 역할만", () => {
    const roles = [role({ id: "r-admin", caps: ["admin.users", "res.read"] }), role({ id: "r-op", caps: ["res.read"] })];
    const ids = adminRoleIds(roles);
    expect(ids.has("r-admin")).toBe(true);
    expect(ids.has("r-op")).toBe(false);
  });
  it("isAdminAction — 관리 행위만 true", () => {
    expect(isAdminAction("user.create")).toBe(true);
    expect(isAdminAction("acl.set")).toBe(true);
    expect(isAdminAction("node.purge")).toBe(true);
    expect(isAdminAction("2fa.admin.reset")).toBe(true);
    expect(isAdminAction("login.success")).toBe(false);
    expect(isAdminAction("note.view")).toBe(false);
  });
});

describe("buildAuditReport — 5분류 마크다운", () => {
  const roles = [
    role({ id: "r-admin", name: "관리자", caps: ["admin.users", "res.read"] }),
    role({ id: "r-op", name: "운영자", caps: ["res.read", "res.edit"] }),
  ];
  const users = [
    user({ id: "ua", emp: "admin", name: "관리자", roleId: "r-admin", lastLogin: "2026-06-20T21:31:14" }),
    user({ id: "u1", emp: "E1001", name: "이영희", roleId: "r-op", lastLogin: "2026-06-10T09:00:00" }),
    user({ id: "u2", emp: "E1002", name: "박민수", roleId: "r-op", lastLogin: null }),
  ];
  const rows: ApiAudit[] = [
    audit({ id: 1, act: "login.success", who: "admin", at: "2026-06-20T21:31:14", ip: "10.0.0.1" }),
    audit({ id: 2, act: "login.success", who: "E1001", at: "2026-06-10T09:00:00", ip: "10.0.0.2" }),
    audit({ id: 3, act: "login.fail", who: "E1001", at: "2026-06-10T08:59:00", ip: "10.0.0.2" }),
    audit({ id: 4, act: "note.view", who: "E1001", at: "2026-06-19T10:00:00", target: "분기 보고서" }),
    audit({ id: 5, act: "note.view", who: "E1001", at: "2026-06-20T22:00:00", target: "야간 메모" }), // 주말 → off
    audit({ id: 6, act: "attachment.download", who: "E1001", at: "2026-06-19T07:30:00", target: "n1" }), // 07:30 → off
    audit({ id: 7, act: "user.create", who: "admin", at: "2026-06-05T11:00:00", target: "E1003" }),
    audit({ id: 8, act: "acl.set", who: "admin", at: "2026-06-06T12:00:00", target: "n1 (2건)" }),
  ];
  const md = buildAuditReport({ year: 2026, month: 6, rows, users, roles, generatedAt: "2026-06-20 23:00:00" });

  it("헤더·요약", () => {
    expect(md).toContain("# WorkNote 월간 감사 리포트 — 2026-06");
    expect(md).toContain("총 감사 이벤트: 8건");
    expect(md).toContain("등록 사용자: 3명 (관리자 1명)");
  });
  it("1. 로그 관리 — 로그인 집계", () => {
    expect(md).toContain("## 1. 로그 관리");
    expect(md).toContain("로그인 성공 2건 · 로그인 실패 1건");
  });
  it("3. 관리자 — 작업/계정 수", () => {
    expect(md).toContain("관리자 작업 수행: 2건"); // user.create + acl.set
    expect(md).toContain("관리자 계정 수: **1개**");
    expect(md).toContain("정상 (4개 미만");
  });
  it("4. 계정 — 변동/미접속 일수", () => {
    expect(md).toContain("당월 계정 변동: 1건");
    expect(md).toContain("| 2026-06-10 | 10 |"); // E1001 미접속 일수 10일(06-10 → 06-20)
  });
  it("5. 조회/다운로드 — 계측·업무시간 외", () => {
    expect(md).toContain("## 5. 조회/다운로드 이력");
    expect(md).toContain("노트 조회(view): 총 2건");
    expect(md).toContain("다운로드(첨부+내보내기): 총 1건");
    expect(md).toContain("업무시간 외(평일 08–19시 외·주말) 조회/다운로드: 2건");
  });
  it("PDF용 HTML로도 변환된다", () => {
    const doc = buildReportHtmlDoc("리포트", md);
    expect(doc).toContain("<h1>WorkNote 월간 감사 리포트 — 2026-06</h1>");
    expect(doc).toContain("<h2>5. 조회/다운로드 이력</h2>");
    expect(doc).toContain("<strong>1개</strong>"); // 관리자 계정 수
  });
  it("관리자 4명 이상이면 소명 경고", () => {
    const manyAdmin = [
      role({ id: "r-admin", name: "관리자", caps: ["admin.users"] }),
    ];
    const adminUsers = ["a1", "a2", "a3", "a4"].map((e, i) =>
      user({ id: e, emp: e, roleId: "r-admin", name: "관리자" + i }));
    const out = buildAuditReport({ year: 2026, month: 6, rows: [], users: adminUsers, roles: manyAdmin, generatedAt: "2026-06-20 23:00:00" });
    expect(out).toContain("관리자 계정 수: **4개**");
    expect(out).toContain("소명 및 부서장 승인 이력이 필요");
  });
});

describe("reportMarkdownToHtml — PDF용 부분집합 변환", () => {
  it("헤딩 #/##", () => {
    expect(reportMarkdownToHtml("# 제목")).toContain("<h1>제목</h1>");
    expect(reportMarkdownToHtml("## 절")).toContain("<h2>절</h2>");
  });
  it("불릿 + 들여쓰기는 sub", () => {
    expect(reportMarkdownToHtml("- 상위\n  - 하위"))
      .toContain('<ul><li>상위</li><li class="sub">하위</li></ul>');
  });
  it("표 — 헤더 + 우측정렬(---:)", () => {
    const html = reportMarkdownToHtml("| 사용자 | 조회수 |\n| --- | ---: |\n| 김 | 5 |");
    expect(html).toContain("<th>사용자</th>");
    expect(html).toContain('<th class="r">조회수</th>');
    expect(html).toContain('<td class="r">5</td>');
  });
  it("굵게 + HTML 이스케이프", () => {
    expect(reportMarkdownToHtml("- 계정 수: **3개**")).toContain("<strong>3개</strong>");
    expect(reportMarkdownToHtml("- a<b>&c")).toContain("a&lt;b&gt;&amp;c");
  });
  it("셀 내 이스케이프된 파이프(\\|) 복원", () => {
    expect(reportMarkdownToHtml("| 대상 | x |\n| --- | --- |\n| a\\|b | 1 |"))
      .toContain("<td>a|b</td>");
  });
  it("buildReportHtmlDoc — doctype·title·본문 포함", () => {
    const doc = buildReportHtmlDoc("리포트", "# 제목");
    expect(doc.startsWith("<!doctype html>")).toBe(true);
    expect(doc).toContain("<title>리포트</title>");
    expect(doc).toContain("<h1>제목</h1>");
  });
});
