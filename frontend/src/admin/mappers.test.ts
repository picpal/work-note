import { describe, it, expect } from "vitest";
import { statusLabel, capLabel, actLabel, actType, roleName, KNOWN_CAPS, KNOWN_ACTS } from "./mappers";

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
  it("KNOWN_ACTS는 31종이고 전부 ACTS 라벨이 존재(드리프트 가드)", () => {
    expect(KNOWN_ACTS).toHaveLength(31);
    for (const a of KNOWN_ACTS) expect(actLabel(a), a + " 라벨 누락").not.toBe(a);
  });
  it("공유 링크 감사 라벨 3종", () => {
    expect(actLabel("share.create")).toBe("공유 링크 생성");
    expect(actLabel("share.view")).toBe("공유 링크 열람");
    expect(actLabel("share.revoke")).toBe("공유 링크 취소");
  });
  it("actLabel은 dot 명명을 한국어로, 미지 act는 원문", () => {
    expect(actLabel("login.success")).toBe("로그인");
    expect(actLabel("user.approve")).toBe("계정 승인");
    expect(actLabel("unknown.act")).toBe("unknown.act");
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
  });
  it("roleName은 roles에서 찾고 없으면 id", () => {
    expect(roleName("admin", [{ id: "admin", name: "관리자", system: true, caps: [], userCount: 1 }])).toBe("관리자");
    expect(roleName("ghost", [])).toBe("ghost");
  });
});
