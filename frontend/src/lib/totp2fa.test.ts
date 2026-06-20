import { describe, it, expect } from "vitest";
import { canEnroll, enrollBlockReason, mustEnrollNow, shouldNudge } from "./totp2fa";

describe("totp2fa 화면 판정", () => {
  it("이메일 없으면 등록 불가 + 사유", () => {
    expect(canEnroll({ enabled: false, enforced: false, graceExpired: false, emailPresent: false })).toBe(false);
    expect(enrollBlockReason({ enabled: false, enforced: false, graceExpired: false, emailPresent: false }))
      .toContain("이메일");
  });
  it("이메일 있으면 등록 가능", () => {
    expect(canEnroll({ enabled: false, enforced: false, graceExpired: false, emailPresent: true })).toBe(true);
  });
  it("이메일 있으면 차단 사유 없음(null)", () => {
    expect(enrollBlockReason({ enabled: false, enforced: false, graceExpired: false, emailPresent: true })).toBeNull();
  });
  it("enforced+graceExpired면 강제 등록 화면", () => {
    expect(mustEnrollNow({ enabled: false, enforced: true, graceExpired: true, emailPresent: true })).toBe(true);
    expect(mustEnrollNow({ enabled: false, enforced: true, graceExpired: false, emailPresent: true })).toBe(false);
    expect(mustEnrollNow({ enabled: true, enforced: false, graceExpired: false, emailPresent: true })).toBe(false);
  });
  it("enforced+graceExpired 아니면 강제 아님", () => {
    expect(mustEnrollNow({ enabled: false, enforced: false, graceExpired: false, emailPresent: false })).toBe(false);
  });
  it("shouldNudge: enforced + 미등록 + 유예중", () => {
    expect(shouldNudge({ enabled: false, enforced: true, graceExpired: false, emailPresent: true })).toBe(true);
    expect(shouldNudge({ enabled: true, enforced: true, graceExpired: false, emailPresent: true })).toBe(false);
    expect(shouldNudge({ enabled: false, enforced: true, graceExpired: true, emailPresent: true })).toBe(false);
  });
});
