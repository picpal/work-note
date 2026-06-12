import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import { req, ApiError, setOn401 } from "./http";

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
