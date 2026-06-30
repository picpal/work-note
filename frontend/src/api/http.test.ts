import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import { req, ApiError, setOn401, is2faEnrollmentRequired, setOn2faRequired } from "./http";

function jsonRes(status: number, body?: unknown): Response {
  return {
    ok: status >= 200 && status < 300,
    status,
    json: () => (body === undefined ? Promise.reject(new Error("no body")) : Promise.resolve(body)),
  } as unknown as Response;
}

describe("req", () => {
  beforeEach(() => { vi.stubGlobal("fetch", vi.fn()); });
  afterEach(() => { vi.unstubAllGlobals(); setOn401(null); });

  it("성공 시 JSON 반환, /api prefix와 Content-Type 적용", async () => {
    (fetch as ReturnType<typeof vi.fn>).mockResolvedValue(jsonRes(200, { a: 1 }));
    const out = await req<{ a: number }>("/tree");
    expect(out).toEqual({ a: 1 });
    expect(fetch).toHaveBeenCalledWith("/api/tree", expect.objectContaining({
      headers: expect.objectContaining({ "Content-Type": "application/json" }),
    }));
  });

  it("204는 undefined", async () => {
    (fetch as ReturnType<typeof vi.fn>).mockResolvedValue(jsonRes(204));
    await expect(req<void>("/x", { method: "POST" })).resolves.toBeUndefined();
  });

  it("에러 바디의 error 메시지로 ApiError", async () => {
    (fetch as ReturnType<typeof vi.fn>).mockResolvedValue(jsonRes(409, { error: "중복" }));
    await expect(req("/x")).rejects.toMatchObject({ status: 409, message: "중복" });
  });

  it("에러 바디가 JSON이 아니면 HTTP n 메시지 — non-401은 on401 미호출", async () => {
    const handler = vi.fn();
    setOn401(handler);
    (fetch as ReturnType<typeof vi.fn>).mockResolvedValue(jsonRes(500));
    await expect(req("/x")).rejects.toMatchObject({ status: 500, message: "HTTP 500" });
    expect(handler).not.toHaveBeenCalled();
  });

  it("401이면 on401 핸들러 호출 후에도 ApiError throw", async () => {
    const handler = vi.fn();
    setOn401(handler);
    (fetch as ReturnType<typeof vi.fn>).mockResolvedValue(jsonRes(401, { error: "인증이 필요합니다" }));
    await expect(req("/x")).rejects.toBeInstanceOf(ApiError);
    expect(handler).toHaveBeenCalledOnce();
  });

  it("on401 미설치면 401도 그냥 throw", async () => {
    (fetch as ReturnType<typeof vi.fn>).mockResolvedValue(jsonRes(401, {}));
    await expect(req("/x")).rejects.toMatchObject({ status: 401 });
  });
});

describe("is2faEnrollmentRequired", () => {
  it("403 + 2fa_enrollment_required 코드면 true", () => {
    expect(is2faEnrollmentRequired(403, "2fa_enrollment_required")).toBe(true);
  });
  it("다른 403(권한 거부)은 false", () => {
    expect(is2faEnrollmentRequired(403, "권한이 없습니다")).toBe(false);
    expect(is2faEnrollmentRequired(403, undefined)).toBe(false);
  });
  it("403 외 상태는 false", () => {
    expect(is2faEnrollmentRequired(401, "2fa_enrollment_required")).toBe(false);
    expect(is2faEnrollmentRequired(200, "2fa_enrollment_required")).toBe(false);
  });
});

describe("req 2fa_enrollment_required 핸들러", () => {
  beforeEach(() => { vi.stubGlobal("fetch", vi.fn()); });
  afterEach(() => { vi.unstubAllGlobals(); setOn401(null); setOn2faRequired(null); });

  it("403 2fa_enrollment_required 시 on2faRequired 호출 + ApiError throw", async () => {
    const h = vi.fn();
    setOn2faRequired(h);
    (fetch as ReturnType<typeof vi.fn>).mockResolvedValue(jsonRes(403, { error: "2fa_enrollment_required" }));
    await expect(req("/admin/users")).rejects.toBeInstanceOf(ApiError);
    expect(h).toHaveBeenCalledTimes(1);
  });

  it("다른 403은 on2faRequired 미호출", async () => {
    const h = vi.fn();
    setOn2faRequired(h);
    (fetch as ReturnType<typeof vi.fn>).mockResolvedValue(jsonRes(403, { error: "권한이 없습니다" }));
    await expect(req("/admin/users")).rejects.toBeInstanceOf(ApiError);
    expect(h).not.toHaveBeenCalled();
  });
});
