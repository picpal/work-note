import { describe, it, expect } from "vitest";
import { validateProfile } from "./profileValidation";

describe("validateProfile", () => {
  it("빈 이름 거부", () => { expect(validateProfile("   ")).toMatch(/이름/); });
  it("64자 초과 거부", () => { expect(validateProfile("a".repeat(65))).toMatch(/64자/); });
  it("정상 통과", () => { expect(validateProfile("홍길동")).toBeNull(); });
});
