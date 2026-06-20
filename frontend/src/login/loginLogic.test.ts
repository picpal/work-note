import { describe, it, expect, vi } from "vitest";
import { validateSignup, submitLogin, submitSignup, submitLogin2fa, submitVerify2fa, submitRecover } from "./loginLogic";
import { ApiError } from "../api/http";

describe("validateSignup", () => {
  it("필수 필드 누락이면 메시지", () => {
    expect(validateSignup({ emp: "", name: "n", email: "", password: "12345678", password2: "12345678" }))
      .toContain("사번");
  });
  it("비밀번호 9자 이하이면 메시지", () => {
    expect(validateSignup({ emp: "S1", name: "n", email: "", password: "123456789", password2: "123456789" }))
      .toContain("10자");
  });
  it("비밀번호 10자이면 길이 통과", () => {
    expect(validateSignup({ emp: "S1", name: "n", email: "", password: "1234567890", password2: "1234567890" }))
      .toBeNull();
  });
  it("비밀번호 불일치면 메시지", () => {
    expect(validateSignup({ emp: "S1", name: "n", email: "", password: "1234567890", password2: "1234567891" }))
      .toContain("일치");
  });
  it("정상이면 null", () => {
    expect(validateSignup({ emp: "S1", name: "n", email: "", password: "1234567890", password2: "1234567890" }))
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
  it("ApiError가 아닌 에러(네트워크)면 일반 메시지 반환", async () => {
    const api = { login: vi.fn().mockRejectedValue(new TypeError("fetch failed")) };
    const onSuccess = vi.fn();
    const err = await submitLogin(api as never, "S1", "pw", onSuccess);
    expect(err).toBe("서버에 연결할 수 없습니다");
    expect(onSuccess).not.toHaveBeenCalled();
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

describe("submitLogin2fa", () => {
  it("완전 인증이면 navigate=true", async () => {
    const api = { login: vi.fn().mockResolvedValue({ id: "u1", emp: "10001", caps: [] }) };
    const r = await submitLogin2fa(api as any, "10001", "pw");
    expect(r).toEqual({ kind: "ok" });
  });
  it("2fa_required면 challenge 단계", async () => {
    const api = { login: vi.fn().mockResolvedValue({ status: "2fa_required" }) };
    const r = await submitLogin2fa(api as any, "10001", "pw");
    expect(r).toEqual({ kind: "2fa" });
  });
  it("실패면 에러 메시지", async () => {
    const api = { login: vi.fn().mockRejectedValue(new ApiError("사번 또는 비밀번호", 401)) };
    const r = await submitLogin2fa(api as any, "10001", "pw");
    expect(r).toEqual({ kind: "error", message: "사번 또는 비밀번호" });
  });
});

describe("submitVerify2fa", () => {
  it("성공이면 null", async () => {
    const api = { verify2fa: vi.fn().mockResolvedValue({ id: "u1" }) };
    expect(await submitVerify2fa(api as any, "123456")).toBeNull();
  });
  it("공백 포함 코드는 trim 후 호출", async () => {
    const api = { verify2fa: vi.fn().mockResolvedValue({ id: "u1" }) };
    expect(await submitVerify2fa(api as any, " 123456 ")).toBeNull();
    expect(api.verify2fa).toHaveBeenCalledWith("123456");
  });
  it("실패면 에러 메시지", async () => {
    const api = { verify2fa: vi.fn().mockRejectedValue(new ApiError("인증 코드가 올바르지 않습니다", 401)) };
    expect(await submitVerify2fa(api as any, "000000")).toBe("인증 코드가 올바르지 않습니다");
  });
});

describe("submitRecover", () => {
  it("recoverVerify 성공이면 null", async () => {
    const api = { recoverVerify: vi.fn().mockResolvedValue({ id: "u1" }) };
    expect(await submitRecover(api as any, "10001", "12345678")).toBeNull();
  });
  it("recoverVerify 실패면 에러 메시지", async () => {
    const api = {
      recoverVerify: vi.fn().mockRejectedValue(new ApiError("복구 코드가 올바르지 않거나 만료되었습니다", 401)),
    };
    expect(await submitRecover(api as any, "10001", "00000000")).toContain("복구");
  });
});
