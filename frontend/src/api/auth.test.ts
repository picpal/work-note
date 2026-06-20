import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import { AuthApi } from "./auth";
import type { Me } from "./auth";

describe("AuthApi", () => {
  beforeEach(() => { vi.stubGlobal("fetch", vi.fn()); });
  afterEach(() => { vi.unstubAllGlobals(); });

  it("login은 POST /api/auth/login에 emp/password를 보낸다", async () => {
    (fetch as ReturnType<typeof vi.fn>).mockResolvedValue({
      ok: true, status: 201,
      json: () => Promise.resolve({ id: "u1", emp: "S1", name: "n", roleId: "admin", caps: ["admin.users"] }),
    });
    const me = await AuthApi.login("S1", "pw123456") as Me;
    expect(me.roleId).toBe("admin");
    expect(fetch).toHaveBeenCalledWith("/api/auth/login", expect.objectContaining({
      method: "POST", body: JSON.stringify({ emp: "S1", password: "pw123456" }),
    }));
  });

  it("signup은 POST /api/auth/signup", async () => {
    (fetch as ReturnType<typeof vi.fn>).mockResolvedValue({
      ok: true, status: 201, json: () => Promise.resolve({ id: "u9", status: "pending" }),
    });
    const out = await AuthApi.signup({ emp: "S9", name: "신규", email: "a@b", password: "pw123456" });
    expect(out.status).toBe("pending");
  });

  it("logout은 POST /api/auth/logout (204)", async () => {
    (fetch as ReturnType<typeof vi.fn>).mockResolvedValue({ ok: true, status: 204, json: () => Promise.reject(new Error()) });
    await expect(AuthApi.logout()).resolves.toBeUndefined();
  });

  it("me는 GET /api/auth/me", async () => {
    (fetch as ReturnType<typeof vi.fn>).mockResolvedValue({
      ok: true, status: 200,
      json: () => Promise.resolve({ id: "local", emp: "local", name: "local", roleId: "admin", caps: [] }),
    });
    const me = await AuthApi.me();
    expect(me.id).toBe("local");
    expect(fetch).toHaveBeenCalledWith("/api/auth/me", expect.anything());
  });
});

describe("AuthApi.changePassword", () => {
  beforeEach(() => { vi.stubGlobal("fetch", vi.fn()); });
  afterEach(() => { vi.unstubAllGlobals(); });

  it("POST /auth/change-password 로 두 비번을 본문에 담아 호출", async () => {
    (fetch as ReturnType<typeof vi.fn>).mockResolvedValue({ ok: true, status: 204, json: () => Promise.resolve(undefined) });
    await AuthApi.changePassword("cur-pw-123", "new-pw-9999");
    expect(fetch).toHaveBeenCalledWith("/api/auth/change-password", expect.objectContaining({
      method: "POST",
      body: JSON.stringify({ currentPassword: "cur-pw-123", newPassword: "new-pw-9999" }),
    }));
  });

  it("422 응답은 ApiError(status 422, 서버 메시지)로 던진다", async () => {
    (fetch as ReturnType<typeof vi.fn>).mockResolvedValue({
      ok: false, status: 422, json: () => Promise.resolve({ error: "현재 비밀번호가 올바르지 않습니다" }),
    });
    await expect(AuthApi.changePassword("wrong", "new-pw-9999")).rejects.toMatchObject({
      name: "ApiError", status: 422, message: "현재 비밀번호가 올바르지 않습니다",
    });
  });
});

describe("AuthApi.updateProfile", () => {
  beforeEach(() => { vi.stubGlobal("fetch", vi.fn()); });
  afterEach(() => { vi.unstubAllGlobals(); });

  it("POST로 name/email 전송하고 Me 반환", async () => {
    const me = { id: "u1", emp: "10001", name: "새이름", email: "x@corp.local", roleId: "operator", caps: [] };
    (fetch as ReturnType<typeof vi.fn>).mockResolvedValue({ ok: true, status: 200, json: () => Promise.resolve(me) });
    const res = await AuthApi.updateProfile("새이름", "x@corp.local");
    expect(res).toEqual(me);
    const [url, init] = (fetch as ReturnType<typeof vi.fn>).mock.calls[0];
    expect(url).toContain("/auth/update-profile");
    expect(init.method).toBe("POST");
    expect(JSON.parse(init.body)).toEqual({ name: "새이름", email: "x@corp.local" });
  });

  it("빈 email은 body에서 생략", async () => {
    (fetch as ReturnType<typeof vi.fn>).mockResolvedValue({ ok: true, status: 200, json: () => Promise.resolve({}) });
    await AuthApi.updateProfile("이름", "");
    const body = JSON.parse((fetch as ReturnType<typeof vi.fn>).mock.calls[0][1].body);
    expect(body).toEqual({ name: "이름" });
    expect("email" in body).toBe(false);
  });
});
