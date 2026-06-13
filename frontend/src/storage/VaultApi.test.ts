import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import { VaultApi } from "./VaultApi";

function mockJson(body: unknown, status = 200) {
  (fetch as ReturnType<typeof vi.fn>).mockResolvedValue({
    ok: true, status, json: () => Promise.resolve(body),
  });
}

const sample = {
  publicBefore: false, publicAfter: true,
  crossSpace: false, fromSpace: null, toSpace: null,
  added: [], removed: [],
};

describe("VaultApi.movePreview", () => {
  beforeEach(() => { vi.stubGlobal("fetch", vi.fn()); });
  afterEach(() => { vi.unstubAllGlobals(); });

  it("parentId 있으면 ?parentId= 쿼리로 GET", async () => {
    mockJson(sample);
    const out = await VaultApi.movePreview("n1", "p1");
    expect(out.publicAfter).toBe(true);
    expect(fetch).toHaveBeenCalledWith("/api/nodes/n1/move-preview?parentId=p1", expect.anything());
  });

  it("parentId가 null이면 쿼리 없는 경로로 GET (루트 이동)", async () => {
    mockJson(sample);
    await VaultApi.movePreview("n1", null);
    expect(fetch).toHaveBeenCalledWith("/api/nodes/n1/move-preview", expect.anything());
  });

  it("id·parentId 특수문자를 인코딩한다", async () => {
    mockJson(sample);
    await VaultApi.movePreview("a/b c", "x?y&z");
    expect(fetch).toHaveBeenCalledWith(
      "/api/nodes/a%2Fb%20c/move-preview?parentId=x%3Fy%26z",
      expect.anything(),
    );
  });
});
