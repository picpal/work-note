import { describe, it, expect, vi } from "vitest";
import { validateSignup, submitLogin, submitSignup } from "./loginLogic";
import { ApiError } from "../api/http";

describe("validateSignup", () => {
  it("필수 필드 누락이면 메시지", () => {
    expect(validateSignup({ emp: "", name: "n", email: "", password: "12345678", password2: "12345678" }))
      .toContain("사번");
  });
  it("비밀번호 8자 미만이면 메시지", () => {
    expect(validateSignup({ emp: "S1", name: "n", email: "", password: "1234567", password2: "1234567" }))
      .toContain("8자");
  });
  it("비밀번호 불일치면 메시지", () => {
    expect(validateSignup({ emp: "S1", name: "n", email: "", password: "12345678", password2: "12345679" }))
      .toContain("일치");
  });
  it("정상이면 null", () => {
    expect(validateSignup({ emp: "S1", name: "n", email: "", password: "12345678", password2: "12345678" }))
      .toBeNull();
  });
});

describe("submitLogin", () => {
  it("성공 시 onSuccess 호출", async () => {
    const api = { login: vi.fn().mockResolvedValue({ id: "u1" }) };
    const onSuccess = vi.fn();
    const err = await submitLogin(api as never, "S1", "pw", onSuccess);
    expect(err).toBeNull();
    expect(onSuccess).toHaveBeenCalled();
  });
  it("401이면 에러 메시지 반환", async () => {
    const api = { login: vi.fn().mockRejectedValue(new ApiError("사번 또는 비밀번호가 올바르지 않습니다", 401)) };
    const err = await submitLogin(api as never, "S1", "bad", vi.fn());
    expect(err).toContain("올바르지");
  });
});

describe("submitSignup", () => {
  it("409(사번 중복)면 서버 메시지 반환", async () => {
    const api = { signup: vi.fn().mockRejectedValue(new ApiError("이미 존재하는 사번", 409)) };
    const out = await submitSignup(api as never, { emp: "S1", name: "n", email: "", password: "12345678" });
    expect(out.error).toContain("이미 존재");
  });
  it("성공이면 done", async () => {
    const api = { signup: vi.fn().mockResolvedValue({ id: "u9", status: "pending" }) };
    const out = await submitSignup(api as never, { emp: "S9", name: "n", email: "", password: "12345678" });
    expect(out.done).toBe(true);
  });
});
