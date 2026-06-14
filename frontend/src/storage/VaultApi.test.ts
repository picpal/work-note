import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import { VaultApi } from "./VaultApi";

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

describe("VaultApi 휴지통", () => {
  beforeEach(() => { vi.stubGlobal("fetch", vi.fn()); });
  afterEach(() => { vi.unstubAllGlobals(); });

  it("trashList는 GET /api/trash로 목록 반환", async () => {
    mockJson([{ id: "n1", type: "note", title: "삭제됨" }, { id: "f1", type: "folder", name: "옛폴더" }]);
    const out = await VaultApi.trashList();
    expect(out).toHaveLength(2);
    expect(out[0].title).toBe("삭제됨");
    expect(out[1].name).toBe("옛폴더");
    expect(fetch).toHaveBeenCalledWith("/api/trash", expect.anything());
  });

  it("restore는 POST /api/trash/{id}/restore (204)", async () => {
    mock204();
    await VaultApi.restore("n1");
    expect(fetch).toHaveBeenCalledWith("/api/trash/n1/restore", expect.objectContaining({ method: "POST" }));
  });

  it("purge는 DELETE /api/trash/{id} (204)", async () => {
    mock204();
    await VaultApi.purge("n1");
    expect(fetch).toHaveBeenCalledWith("/api/trash/n1", expect.objectContaining({ method: "DELETE" }));
  });

  it("restore는 id 특수문자를 인코딩한다", async () => {
    mock204();
    await VaultApi.restore("a/b");
    expect(fetch).toHaveBeenCalledWith("/api/trash/a%2Fb/restore", expect.anything());
  });
});
