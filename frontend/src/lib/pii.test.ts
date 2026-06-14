import { describe, it, expect } from "vitest";
import { piiWarns, piiTypeLabel } from "./pii";

describe("piiWarns", () => {
  it("suspected/requested/rejected는 경고", () => {
    expect(piiWarns({ status: "suspected", types: [] })).toBe(true);
    expect(piiWarns({ status: "requested", types: [] })).toBe(true);
    expect(piiWarns({ status: "rejected", types: [] })).toBe(true);
  });
  it("exempted/none/null은 비경고", () => {
    expect(piiWarns({ status: "exempted", types: [] })).toBe(false);
    expect(piiWarns({ status: "none", types: [] })).toBe(false);
    expect(piiWarns(null)).toBe(false);
    expect(piiWarns(undefined)).toBe(false);
  });
});

describe("piiTypeLabel", () => {
  it("유형 코드 → 한글 라벨", () => {
    expect(piiTypeLabel("rrn")).toBe("주민등록번호");
    expect(piiTypeLabel("phone")).toBe("휴대폰번호");
    expect(piiTypeLabel("unknown")).toBe("unknown");
  });
});
