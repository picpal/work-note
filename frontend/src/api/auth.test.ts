import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import { AuthApi } from "./auth";

describe("AuthApi", () => {
  beforeEach(() => { vi.stubGlobal("fetch", vi.fn()); });
  afterEach(() => { vi.unstubAllGlobals(); });

  it("login은 POST /api/auth/login에 emp/password를 보낸다", async () => {
    (fetch as ReturnType<typeof vi.fn>).mockResolvedValue({
      ok: true, status: 201,
      json: () => Promise.resolve({ id: "u1", emp: "S1", name: "n", roleId: "admin", caps: ["admin.users"] }),
    });
    const me = await AuthApi.login("S1", "pw123456");
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
