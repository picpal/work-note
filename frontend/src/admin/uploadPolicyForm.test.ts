import { describe, it, expect } from "vitest";
import { bytesToMb, checkExt, checkMaxMb, invalidExts, MAX_UPLOAD_MB, normalizeExt } from "./uploadPolicyForm";

describe("checkExt — 허용 확장자 입력", () => {
  it("점·대문자·공백은 정리해서 받는다", () => {
    expect(normalizeExt(" .PNG ")).toBe("png");
    expect(checkExt(".PNG")).toEqual({ ok: true, ext: "png" });
    expect(checkExt(" Jpg ")).toEqual({ ok: true, ext: "jpg" });
  });
  it("QA 재현: '.BAD!' 는 사유와 함께 거부(칩으로 조용히 들어가지 않는다)", () => {
    const r = checkExt(".BAD!");
    expect(r.ok).toBe(false);
    if (!r.ok) {
      expect(r.reason).toContain(".BAD!");
      expect(r.reason).toContain("영문 소문자·숫자");
    }
  });
  it("형식 위반은 전부 거부", () => {
    for (const bad of ["", "   ", ".", "p ng", "png/", "한글", "a".repeat(17), "pn-g", "pn_g", "*"]) {
      expect(checkExt(bad).ok, "허용되면 안 됨: " + JSON.stringify(bad)).toBe(false);
    }
  });
  it("경계: 16자는 되고 17자는 안 된다", () => {
    expect(checkExt("a".repeat(16)).ok).toBe(true);
    expect(checkExt("a".repeat(17)).ok).toBe(false);
  });
  it("중복은 사유와 함께 거부", () => {
    const r = checkExt(".PNG", ["png", "pdf"]);
    expect(r.ok).toBe(false);
    if (!r.ok) expect(r.reason).toContain("이미 추가");
  });
});

describe("invalidExts — 이미 저장돼 있던 죽은 값", () => {
  it("형식을 어긴 기존 값을 골라낸다", () => {
    expect(invalidExts(["png", "bad!", "pdf", "가나"])).toEqual(["bad!", "가나"]);
    expect(invalidExts(["png", "pdf"])).toEqual([]);
  });
});

describe("checkMaxMb — 파일당 최대 용량", () => {
  it("정상 범위는 그대로, 보정 없음", () => {
    expect(checkMaxMb("25")).toEqual({ ok: true, mb: 25, note: null });
    expect(checkMaxMb(1)).toEqual({ ok: true, mb: 1, note: null });
    expect(checkMaxMb(MAX_UPLOAD_MB)).toEqual({ ok: true, mb: MAX_UPLOAD_MB, note: null });
  });
  it("QA 재현: 99999MB는 상한으로 보정하고 보정 사실을 알린다", () => {
    const r = checkMaxMb("99999");
    expect(r).toMatchObject({ ok: true, mb: MAX_UPLOAD_MB });
    if (r.ok) {
      expect(r.note).toContain("99999");
      expect(r.note).toContain("보정");
    }
  });
  it("QA 재현: 0·음수는 1MB로 보정하되 조용히 넘어가지 않는다", () => {
    for (const bad of ["0", "-5"]) {
      const r = checkMaxMb(bad);
      expect(r).toMatchObject({ ok: true, mb: 1 });
      if (r.ok) expect(r.note, bad + " 보정 안내 없음").toContain("보정");
    }
  });
  it("소수점은 내림하고 그 사실도 알린다", () => {
    const r = checkMaxMb("2.7");
    expect(r).toMatchObject({ ok: true, mb: 2 });
    if (r.ok) expect(r.note).toContain("보정");
  });
  it("빈 값·숫자가 아닌 값은 저장하지 않고 사유를 준다", () => {
    expect(checkMaxMb("")).toEqual({ ok: false, reason: "최대 용량을 입력하세요" });
    expect(checkMaxMb("abc").ok).toBe(false);
  });
});

describe("bytesToMb", () => {
  it("바이트를 MB로 내림하되 범위 안으로", () => {
    expect(bytesToMb(26214400)).toBe(25);
    expect(bytesToMb(5000)).toBe(1);          // 1MB 미만도 최소 1로 표시
    expect(bytesToMb(999 * 1024 * 1024)).toBe(MAX_UPLOAD_MB);
  });
});
