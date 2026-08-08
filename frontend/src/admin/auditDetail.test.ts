import { describe, it, expect } from "vitest";
import { auditDetailLines, hasAuditDetail } from "./auditDetail";

describe("auditDetailLines — acl.set", () => {
  it("added/removed/changed를 +/−/~ 로 전개", () => {
    const detail = JSON.stringify({
      added: [{ p: "team:t-dev", g: "read" }],
      removed: [{ p: "user:u-1", g: "edit" }],
      changed: [{ p: "team:t-qa", from: "read", to: "deny" }],
    });
    expect(auditDetailLines("acl.set", detail)).toEqual([
      { sign: "+", text: "team:t-dev 읽기" },
      { sign: "−", text: "user:u-1 편집" },
      { sign: "~", text: "team:t-qa 읽기→거부" },
    ]);
  });

  it("없는 갈래는 건너뛴다", () => {
    expect(auditDetailLines("acl.set", JSON.stringify({ added: [{ p: "user:u-1", g: "deny" }] })))
      .toEqual([{ sign: "+", text: "user:u-1 거부" }]);
  });

  it("미지의 grant 값은 원문 그대로 노출(데이터 손실 방지)", () => {
    expect(auditDetailLines("acl.set", JSON.stringify({ added: [{ p: "all:@all", g: "write" }] })))
      .toEqual([{ sign: "+", text: "all:@all write" }]);
  });
});

describe("auditDetailLines — role.update", () => {
  it("이름 변경 + caps 증감", () => {
    const detail = JSON.stringify({
      name: { from: "검토자", to: "리뷰어" },
      caps: { added: ["res.delete"], removed: ["res.export"] },
    });
    expect(auditDetailLines("role.update", detail)).toEqual([
      { sign: "~", text: "이름 검토자→리뷰어" },
      { sign: "+", text: "노트 삭제" },
      { sign: "−", text: "내보내기" },
    ]);
  });

  it("caps만 바뀐 경우 이름 줄 없음", () => {
    expect(auditDetailLines("role.update", JSON.stringify({ caps: { added: [], removed: ["res.read"] } })))
      .toEqual([{ sign: "−", text: "노트 열람" }]);
  });
});

describe("auditDetailLines — public.set / public.unset", () => {
  it("이전 모드 없음 → 공개", () => {
    expect(auditDetailLines("public.set", JSON.stringify({ from: null, to: "public" })))
      .toEqual([{ sign: "~", text: "공개 설정 없음→공개" }]);
  });

  it("해제는 to=null", () => {
    expect(auditDetailLines("public.unset", JSON.stringify({ from: "exclude", to: null })))
      .toEqual([{ sign: "~", text: "공개 설정 제외→없음" }]);
  });
});

describe("auditDetailLines — 방어", () => {
  it("null/빈 문자열/깨진 JSON은 빈 배열 (감사 화면이 터지지 않는다)", () => {
    expect(auditDetailLines("acl.set", null)).toEqual([]);
    expect(auditDetailLines("acl.set", undefined)).toEqual([]);
    expect(auditDetailLines("acl.set", "")).toEqual([]);
    expect(auditDetailLines("acl.set", "{not json")).toEqual([]);
    expect(auditDetailLines("acl.set", "[1,2,3]")).toEqual([]);
  });

  it("모르는 act의 detail은 원문 한 줄로 보존", () => {
    expect(auditDetailLines("space.set", '{"x":1}')).toEqual([{ sign: "~", text: '{"x":1}' }]);
  });

  it("기대와 다른 모양(배열 아님·필드 누락)도 던지지 않는다", () => {
    expect(auditDetailLines("acl.set", JSON.stringify({ added: "nope" }))).toEqual([]);
    expect(auditDetailLines("acl.set", JSON.stringify({ added: [{}] })))
      .toEqual([{ sign: "+", text: "? ?" }]);
    expect(auditDetailLines("role.update", JSON.stringify({ name: null }))).toEqual([]);
  });
});

describe("hasAuditDetail", () => {
  it("펼치기 토글을 띄울지 결정 — 전개 결과가 있을 때만", () => {
    expect(hasAuditDetail("acl.set", null)).toBe(false);
    expect(hasAuditDetail("acl.set", "{not json")).toBe(false);
    expect(hasAuditDetail("acl.set", JSON.stringify({ added: [{ p: "user:u-1", g: "read" }] }))).toBe(true);
  });
});
