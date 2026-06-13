import { describe, it, expect } from "vitest";
import { validatePasswordChange } from "./passwordValidation";

describe("validatePasswordChange", () => {
  it("모두 채워지고 10자 이상·일치·현재와 다르면 통과(null)", () => {
    expect(validatePasswordChange("old-pw-123", "new-pw-9999", "new-pw-9999")).toBeNull();
  });
  it("빈 항목이 있으면 안내", () => {
    expect(validatePasswordChange("", "new-pw-9999", "new-pw-9999")).toMatch(/입력/);
  });
  it("새 비번 10자 미만이면 거부", () => {
    expect(validatePasswordChange("old-pw-123", "short", "short")).toMatch(/10자/);
  });
  it("새 비번 확인 불일치면 거부", () => {
    expect(validatePasswordChange("old-pw-123", "new-pw-9999", "different9")).toMatch(/일치/);
  });
  it("새 비번이 현재와 같으면 거부", () => {
    expect(validatePasswordChange("samepass-12", "samepass-12", "samepass-12")).toMatch(/다른/);
  });
});
