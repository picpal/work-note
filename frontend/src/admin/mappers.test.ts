import { describe, it, expect } from "vitest";
import { statusLabel, capLabel, actLabel, actType, roleName, KNOWN_CAPS, KNOWN_ACTS } from "./mappers";
import { AUDIT_ACTS } from "./auditActs";

describe("mappers", () => {
  it("statusLabel", () => {
    expect(statusLabel("active")).toBe("활성");
    expect(statusLabel("disabled")).toBe("비활성");
    expect(statusLabel("pending")).toBe("대기");
  });
  it("capLabel은 미지 cap이면 원문", () => {
    expect(capLabel("admin.users")).toBe("사용자 관리");
    expect(capLabel("res.export")).toBe("내보내기");
    expect(capLabel("x.y")).toBe("x.y");
  });
  it("KNOWN_CAPS는 11종이고 전부 CAPS 라벨이 존재(드리프트 가드)", () => {
    expect(KNOWN_CAPS).toHaveLength(11);
    for (const c of KNOWN_CAPS) expect(capLabel(c), c + " 라벨 누락").not.toBe(c);
  });
  it("KNOWN_ACTS는 auditActs 단일 출처에서 파생 — 라벨 누락이 구조적으로 불가", () => {
    expect(KNOWN_ACTS).toEqual(AUDIT_ACTS.map((d) => d.act));
    for (const a of KNOWN_ACTS) expect(actLabel(a), a + " 라벨 누락").not.toContain("미등록");
  });
  it("조회/다운로드/내보내기 감사 라벨", () => {
    expect(actLabel("note.view")).toBe("노트 조회");
    expect(actLabel("note.export")).toBe("내보내기");
    expect(actLabel("attachment.download")).toBe("첨부 다운로드");
  });
  it("공유 링크 감사 라벨 3종", () => {
    expect(actLabel("share.create")).toBe("공유 링크 생성");
    expect(actLabel("share.view")).toBe("공유 링크 열람");
    expect(actLabel("share.revoke")).toBe("공유 링크 취소");
  });
  it("actLabel은 dot 명명을 한국어로, 미등록 act는 원문 + '미등록' 표식", () => {
    expect(actLabel("login.success")).toBe("로그인");
    expect(actLabel("user.approve")).toBe("계정 승인");
    // 조용한 원시 코드 노출 금지 — 코드는 보존하되 라벨이 없다는 사실을 드러낸다
    expect(actLabel("unknown.act")).toContain("unknown.act");
    expect(actLabel("unknown.act")).toContain("미등록");
  });
  it("QA가 화면에서 원시 코드로 봤던 25종에 라벨이 있다(D-4 회귀)", () => {
    const wasRaw = [
      "2fa.setup", "2fa.enabled", "2fa.disabled", "2fa.disable.blocked", "2fa.admin.reset",
      "2fa.challenge", "2fa.verify.success", "2fa.verify.fail", "2fa.locked",
      "2fa.recover.request", "2fa.recover.success", "2fa.recover.fail", "2fa.grace_start",
      "login.locked", "attachment.add", "attachment.remove",
      "pii.request", "pii.approve", "pii.reject", "pii.notice",
      "settings.upload", "settings.redmine", "redmine.import", "redmine.token.set", "redmine.token.delete",
      "template.system.create", "template.system.update", "template.system.delete",
    ];
    for (const a of wasRaw) {
      expect(actLabel(a), a + " 라벨 누락").not.toContain("미등록");
      expect(KNOWN_ACTS, a + " 필터 목록 누락").toContain(a);
    }
    // pii.view는 라벨만 있고 필터에서 빠져 있던 케이스
    expect(KNOWN_ACTS).toContain("pii.view");
  });
  it("actType은 배지 분류", () => {
    expect(actType("login.fail")).toBe("loginfail");
    expect(actType("user.approve")).toBe("approve");
    expect(actType("acl.set")).toBe("grant");
    expect(actType("user.reset")).toBe("reset");
    expect(actType("login.success")).toBe("login");
    expect(actType("role.delete")).toBe("revoke");
    expect(actType("share.create")).toBe("grant");
    expect(actType("share.revoke")).toBe("revoke");
    expect(actType("share.view")).toBe("etc");
    expect(actType("logout")).toBe("etc");
    expect(actType("user.update")).toBe("etc");
    expect(actType("team.create")).toBe("etc");
    // 잠금·차단 계열도 적색(loginfail) — 보안 담당자가 훑을 때 눈에 걸려야 한다
    expect(actType("login.locked")).toBe("loginfail");
    expect(actType("auth.lockout")).toBe("loginfail");
    expect(actType("2fa.locked")).toBe("loginfail");
    expect(actType("recover.locked")).toBe("loginfail");
    expect(actType("2fa.disable.blocked")).toBe("loginfail");
    expect(actType("auth.break_glass")).toBe("loginfail");
    // 미등록 act는 명명 규칙 폴백만
    expect(actType("ghost.fail")).toBe("loginfail");
    expect(actType("ghost.act")).toBe("etc");
  });
  it("roleName은 roles에서 찾고 없으면 id", () => {
    expect(roleName("admin", [{ id: "admin", name: "관리자", system: true, caps: [], userCount: 1 }])).toBe("관리자");
    expect(roleName("ghost", [])).toBe("ghost");
  });
});
