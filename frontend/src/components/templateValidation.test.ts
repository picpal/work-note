import { describe, it, expect } from "vitest";
import { validateTemplateName, validateTemplateBody, NAME_MAX, BODY_MAX } from "./templateValidation";

describe("validateTemplateName", () => {
  it("공백 이름 거부", () => { expect(validateTemplateName("   ")).toMatch(/이름/); });
  it("상한 초과 거부", () => { expect(validateTemplateName("가".repeat(NAME_MAX + 1))).toMatch(/50자/); });
  it("상한 경계는 통과", () => { expect(validateTemplateName("가".repeat(NAME_MAX))).toBeNull(); });
  it("정상 통과", () => { expect(validateTemplateName("주간보고")).toBeNull(); });
});

describe("validateTemplateBody", () => {
  it("빈 본문 거부", () => { expect(validateTemplateBody("\n  \n")).toMatch(/본문/); });
  it("상한 초과 거부", () => { expect(validateTemplateBody("a".repeat(BODY_MAX + 1))).toMatch(/본문/); });
  it("정상 통과", () => { expect(validateTemplateBody("## 안건\n- \n")).toBeNull(); });
});
