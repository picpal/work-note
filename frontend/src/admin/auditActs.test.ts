import { describe, it, expect } from "vitest";
import { AUDIT_ACTS, AUDIT_ACT_GROUP_LABEL, auditActDef, auditActsByGroup } from "./auditActs";
import { scanAuditActs, splitArgs, stripJavaComments } from "./auditActScan";

describe("auditActs 레지스트리 불변식", () => {
  it("act 코드는 중복이 없다", () => {
    const seen = new Set<string>();
    for (const d of AUDIT_ACTS) {
      expect(seen.has(d.act), "중복 act: " + d.act).toBe(false);
      seen.add(d.act);
    }
  });
  it("모든 항목에 비어있지 않은 한글 라벨이 있고, 라벨이 코드와 같지 않다", () => {
    for (const d of AUDIT_ACTS) {
      expect(d.label.trim().length, d.act + " 라벨 없음").toBeGreaterThan(0);
      expect(d.label, d.act + " 라벨이 코드 그대로").not.toBe(d.act);
    }
  });
  it("라벨도 중복이 없다 — 필터 드롭다운에서 구분 불가한 항목이 생기지 않게", () => {
    const seen = new Map<string, string>();
    for (const d of AUDIT_ACTS) {
      expect(seen.has(d.label), `라벨 중복: ${d.label} (${seen.get(d.label)} / ${d.act})`).toBe(false);
      seen.set(d.label, d.act);
    }
  });
  it("group은 라벨 맵에 등록돼 있고, 같은 group이 흩어져 있지 않다(optgroup 분해 전제)", () => {
    const seen = new Set<string>();
    let prev = "";
    for (const d of AUDIT_ACTS) {
      expect(AUDIT_ACT_GROUP_LABEL[d.group], d.act + " group 라벨 없음").toBeTruthy();
      if (d.group !== prev) {
        expect(seen.has(d.group), "group이 흩어져 있음: " + d.group).toBe(false);
        seen.add(d.group);
        prev = d.group;
      }
    }
    expect(auditActsByGroup().reduce((n, g) => n + g.acts.length, 0)).toBe(AUDIT_ACTS.length);
  });
  it("auditActDef는 등록 코드만 반환", () => {
    expect(auditActDef("acl.set")?.label).toBe("권한 설정");
    expect(auditActDef("ghost.act")).toBeUndefined();
  });
});

describe("auditActScan — 백엔드 소스 스캐너", () => {
  it("audit.log / audit.logRaw의 2번째 인자를 뽑는다", () => {
    const src = `
      audit.log(u, "2fa.setup", null, http.getRemoteAddr());
      audit.logRaw(req.emp(), "login.fail", null, ip);
    `;
    expect(scanAuditActs(src).acts).toEqual(["2fa.setup", "login.fail"]);
  });
  it("여러 줄에 걸친 호출·중첩 괄호·인자 속 콤마를 견딘다", () => {
    const src = `
      audit.log(u, "settings.upload",
          "ext=" + body.allowedExt().size() + " max=" + body.maxBytes(), req.getRemoteAddr());
      audit.log(actor, "acl.set", nodeId + " (" + entries.size() + "건)", ip, delta.acl(a, b));
    `;
    expect(scanAuditActs(src).acts).toEqual(["settings.upload", "acl.set"]);
  });
  it("주석 속 호출은 세지 않는다 — 실제로 기록되지 않으므로", () => {
    const src = `
      // local 모드는 audit.log(null) no-op이다
      /* audit.log(u, "ghost.act", null, ip); */
      audit.log(u, "node.create", id, ip);
    `;
    const r = scanAuditActs(src);
    expect(r.acts).toEqual(["node.create"]);
    expect(r.dynamic).toEqual([]);
  });
  it("문자열 안의 // 는 주석이 아니다", () => {
    expect(stripJavaComments('String u = "http://x/y"; // 뒤\nnext;')).toBe('String u = "http://x/y"; \nnext;');
    // 주석 속 audit.log도 문자열 리터럴 속 audit.log도 기록이 아니다
    expect(scanAuditActs('String s = "audit.log(u, \\"x.y\\", null, ip)";').acts).toEqual([]);
  });
  it("act가 리터럴이 아니면 dynamic으로 보고한다(조용한 누락 금지)", () => {
    const r = scanAuditActs('audit.logRaw(user.emp(), act, null, ip);', "X.java");
    expect(r.acts).toEqual([]);
    expect(r.dynamic).toHaveLength(1);
    expect(r.dynamic[0]).toContain("X.java");
  });
  it("AuditService 필드명이 audit이 아니면 stray로 보고한다(스캔 사각지대)", () => {
    const r = scanAuditActs("private final AuditService auditor;", "Y.java");
    expect(r.strayFields).toHaveLength(1);
    expect(scanAuditActs("private final AuditService audit;").strayFields).toEqual([]);
  });
  it("텍스트 블록은 다루지 못한다고 알린다", () => {
    expect(scanAuditActs('String s = """\nx\n""";').unsupported).toHaveLength(1);
  });
  it("splitArgs는 괄호가 안 닫히면 null", () => {
    expect(splitArgs("f(a, b", 1)).toBeNull();
    expect(splitArgs("f(a, g(b, c), d)", 1)).toEqual(["a", "g(b, c)", "d"]);
  });
});
