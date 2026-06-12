import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import { ShareApi, shareUrl } from "./share";

function mockJson(body: unknown, status = 200) {
  (fetch as ReturnType<typeof vi.fn>).mockResolvedValue({
    ok: true, status, json: () => Promise.resolve(body),
  });
}
function mock204() {
  (fetch as ReturnType<typeof vi.fn>).mockResolvedValue({
    ok: true, status: 204, json: () => Promise.reject(new Error()),
  });
}

describe("ShareApi", () => {
  beforeEach(() => { vi.stubGlobal("fetch", vi.fn()); });
  afterEach(() => { vi.unstubAllGlobals(); });

  it("create는 POST /api/nodes/{id}/share에 본문 전달(201)", async () => {
    mockJson({ id: "s1", token: "tok1", expiresAt: "2026-06-19 00:00:00" }, 201);
    const out = await ShareApi.create("n1", { days: 7, maxViews: 10, pinEmps: ["S1"] });
    expect(out.token).toBe("tok1");
    expect(fetch).toHaveBeenCalledWith("/api/nodes/n1/share", expect.objectContaining({
      method: "POST", body: JSON.stringify({ days: 7, maxViews: 10, pinEmps: ["S1"] }),
    }));
  });

  it("listForNode/revoke/view 경로·메서드 (204 undefined 포함)", async () => {
    mockJson([{ id: "s1", token: "tok1", expiresAt: "e", maxViews: null, viewCount: 0, pinEmps: null, createdBy: "S1", createdAt: "c" }]);
    const list = await ShareApi.listForNode("n1");
    expect(list[0].id).toBe("s1");
    expect(fetch).toHaveBeenCalledWith("/api/nodes/n1/shares", expect.anything());

    mock204();
    await expect(ShareApi.revoke("s1")).resolves.toBeUndefined();
    expect(fetch).toHaveBeenCalledWith("/api/shares/s1", expect.objectContaining({ method: "DELETE" }));

    mockJson({ name: "노트", content: "# 본문", updatedAt: null });
    const view = await ShareApi.view("tok1");
    expect(view.name).toBe("노트");
    expect(fetch).toHaveBeenCalledWith("/api/share/tok1", expect.anything());
  });

  it("특수문자는 경로에 인코딩한다", async () => {
    mockJson({ name: "n", content: "", updatedAt: null });
    await ShareApi.view("a/b");
    expect(fetch).toHaveBeenCalledWith("/api/share/a%2Fb", expect.anything());
  });

  it("shareUrl은 현재 디렉토리 기준으로 share.html 링크를 조립한다", () => {
    expect(shareUrl("abc", "http://10.0.0.1:8080", "/wn/index.html"))
      .toBe("http://10.0.0.1:8080/wn/share.html?token=abc");
    expect(shareUrl("a/b", "http://h", "/index.html"))
      .toBe("http://h/share.html?token=a%2Fb");
  });
});
