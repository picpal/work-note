import { describe, it, expect } from "vitest";
import { csvCell, auditDetailCsvCell, buildAuditCsv, fmtAuditAt, DETAIL_SEP } from "./auditExport";
import type { ApiAudit } from "./api";

const row = (over: Partial<ApiAudit>): ApiAudit =>
  ({ id: 1, at: "2026-06-01T10:00:00", who: "10001", act: "login.success", target: null, ip: "10.0.0.1", ...over });

describe("csvCell — RFC4180 이스케이프", () => {
  it("항상 따옴표로 감싸고 내부 따옴표는 두 번", () => {
    expect(csvCell("가나")).toBe('"가나"');
    expect(csvCell('그는 "예"라고')).toBe('"그는 ""예""라고"');
  });

  it("null/undefined는 빈 칸", () => {
    expect(csvCell(null)).toBe('""');
    expect(csvCell(undefined)).toBe('""');
  });

  it("쉼표·줄바꿈이 있어도 따옴표 안이라 칸이 쪼개지지 않는다", () => {
    expect(csvCell("a,b")).toBe('"a,b"');
  });
});

describe("csvCell — 수식 주입(CSV injection) 차단", () => {
  it("= + - @ 로 시작하는 칸은 텍스트 마커(')를 붙인다", () => {
    // 따옴표로 감싸는 것만으로는 못 막는다 — Excel이 따옴표를 벗긴 뒤 수식으로 평가한다
    expect(csvCell('=cmd|\'/c calc\'!A1')).toBe('"\'=cmd|\'/c calc\'!A1"');
    expect(csvCell("+1+1")).toBe('"\'+1+1"');
    expect(csvCell("-2+3")).toBe('"\'-2+3"');
    expect(csvCell("@SUM(A1:A9)")).toBe('"\'@SUM(A1:A9)"');
  });

  it("선행 탭·CR·LF도 수식 트리거라 동일 처리", () => {
    expect(csvCell("\t=1+1")).toBe('"\'\t=1+1"');
    expect(csvCell("\r=1+1")).toBe('"\'\r=1+1"');
    expect(csvCell("\n=1+1")).toBe('"\'\n=1+1"');
  });

  it("전각 변형(＝ ＋ － ＠)도 무해화한다", () => {
    // 한글 IME에서 쉽게 입력되고, Excel은 전각을 반각으로 접어 수식으로 평가한다.
    expect(csvCell("＝1+1")).toBe('"\'＝1+1"');
    expect(csvCell("＋1+1")).toBe('"\'＋1+1"');
    expect(csvCell("－2+3")).toBe('"\'－2+3"');
    expect(csvCell("＠SUM(A1:A9)")).toBe('"\'＠SUM(A1:A9)"');
  });

  it("트리거 앞의 공백은 방어를 우회하지 못한다", () => {
    // Excel은 칸 선두 공백을 무시하고 그 뒤를 수식으로 본다 — 선두 문자만 보면 뚫린다.
    expect(csvCell(" =1+1")).toBe('"\' =1+1"');
    expect(csvCell("  \t =cmd|'/c calc'!A1")).toBe('"\'  \t =cmd|\'/c calc\'!A1"');
    expect(csvCell(" ＝1+1")).toBe('"\' ＝1+1"');
    expect(csvCell("\n  @SUM(A1)")).toBe('"\'\n  @SUM(A1)"');
  });

  it("정상 값에는 접두어를 붙이지 않는다 (증적 원문 보존)", () => {
    expect(csvCell("10001")).toBe('"10001"');
    expect(csvCell("2026-06-01 10:00:00")).toBe('"2026-06-01 10:00:00"');
    expect(csvCell("—")).toBe('"—"');            // em dash(U+2014)는 하이픈이 아니다
    expect(csvCell("권한 설정")).toBe('"권한 설정"');
    expect(csvCell("f1 (1건)")).toBe('"f1 (1건)"');
  });

  it("공백은 그 자체로 트리거가 아니다 (과잉 무해화 방지)", () => {
    // 선행 공백 규칙이 '공백으로 시작하면 무조건'으로 넓어지면 정상 값이 훼손된다.
    expect(csvCell(" 10001")).toBe('" 10001"');
    expect(csvCell("   ")).toBe('"   "');
    expect(csvCell(" 권한 설정")).toBe('" 권한 설정"');
  });
});

describe("auditDetailCsvCell — 델타를 감사자가 읽는 한 줄로", () => {
  it("부호를 한글 라벨로 바꾸고 한 칸에 이어 붙인다", () => {
    const detail = JSON.stringify({
      added: [{ p: "team:t-dev", g: "read" }],
      removed: [{ p: "user:u-1", g: "edit" }],
      changed: [{ p: "team:t-qa", from: "read", to: "deny" }],
    });
    expect(auditDetailCsvCell("acl.set", detail)).toBe(
      ["추가 team:t-dev 읽기", "회수 user:u-1 편집", "변경 team:t-qa 읽기→거부"].join(DETAIL_SEP));
  });

  it("델타가 없으면 빈 문자열", () => {
    expect(auditDetailCsvCell("login.success", null)).toBe("");
    expect(auditDetailCsvCell("acl.set", "{not json")).toBe("");
  });

  it("구분자에 CSV 구분자·수식 문자가 없어 라운드트립이 깨지지 않는다", () => {
    expect(DETAIL_SEP).not.toMatch(/[",;\r\n=+@]/);
  });
});

describe("fmtAuditAt", () => {
  it("T를 공백으로, 초 단위까지만 (마이크로초 절단)", () => {
    expect(fmtAuditAt("2026-06-01T10:00:00")).toBe("2026-06-01 10:00:00");
    expect(fmtAuditAt("2026-06-01T10:00:00.123456")).toBe("2026-06-01 10:00:00");
  });
});

describe("buildAuditCsv", () => {
  it("헤더 6열 + 행, CRLF 구분", () => {
    const csv = buildAuditCsv([row({ act: "acl.set", target: "f1 (1건)",
      detail: JSON.stringify({ added: [{ p: "team:t1", g: "read" }] }) })]);
    const lines = csv.split("\r\n");
    expect(lines[0]).toBe('"일시","행위자","행위","대상","IP/단말","변경 내역"');
    expect(lines[1]).toBe(
      '"2026-06-01 10:00:00","10001","권한 설정","f1 (1건)","10.0.0.1","추가 team:t1 읽기"');
  });

  it("target 없으면 —, 델타 없으면 빈 칸", () => {
    expect(buildAuditCsv([row({})]).split("\r\n")[1])
      .toBe('"2026-06-01 10:00:00","10001","로그인","—","10.0.0.1",""');
  });

  it("미인증 공격자가 심은 행위자 값이 무해화된다", () => {
    // AuthController.java:88 — login.fail은 시도된 emp를 그대로 who에 기록하고
    // LoginRequest.emp에는 문자 패턴 제약이 없다(@Size만). 즉 로그인만 시도하면
    // 임의 문자열을 증적 CSV의 행위자 칸 맨 앞에 심을 수 있다.
    const csv = buildAuditCsv([row({ who: "=cmd|'/c calc'!A1", act: "login.fail" })]);
    expect(csv.split("\r\n")[1]).toContain('"\'=cmd|\'/c calc\'!A1"');
  });

  it("칸 중간의 = 는 수식이 아니므로 원문을 보존한다", () => {
    // Excel은 칸의 '첫 글자'만 수식 트리거로 본다. 부호를 한글 라벨(추가/회수/변경)로
    // 적은 덕에 델타 칸은 구조적으로 한글로 시작한다 → 역할명에 =를 넣어도 칸 선두가 아니다.
    const csv = buildAuditCsv([row({ act: "role.update", target: "r-1",
      detail: JSON.stringify({ name: { from: "검토자", to: "=cmd|'/c calc'!A1" } }) })]);
    expect(csv.split("\r\n")[1]).toContain('"변경 이름 검토자→=cmd|\'/c calc\'!A1"');
  });

  it("빈 목록도 헤더만 있는 유효한 CSV", () => {
    expect(buildAuditCsv([])).toBe('"일시","행위자","행위","대상","IP/단말","변경 내역"');
  });
});
