import { describe, it, expect, vi, beforeEach } from "vitest";
import { AttachmentApi } from "./AttachmentApi";

function mockFetch(status: number, body: unknown) {
  return vi.fn().mockResolvedValue({
    ok: status >= 200 && status < 300, status,
    json: () => Promise.resolve(body),
  });
}

describe("AttachmentApi", () => {
  beforeEach(() => { vi.restoreAllMocks(); });

  it("upload는 FormData를 multipart로 POST한다", async () => {
    const f = mockFetch(201, { id: "att-1", filename: "a.png", size: 3, url: "/api/attachments/att-1" });
    vi.stubGlobal("fetch", f);
    const file = new File([new Uint8Array([1, 2, 3])], "a.png", { type: "image/png" });
    const res = await AttachmentApi.upload("n1", file);
    expect(res.url).toBe("/api/attachments/att-1");
    const [path, init] = f.mock.calls[0];
    expect(path).toBe("/api/nodes/n1/attachments");
    expect(init.method).toBe("POST");
    expect(init.body).toBeInstanceOf(FormData);
    // Content-Type을 직접 설정하지 않는다 (브라우저가 boundary 부여)
    expect(init.headers?.["Content-Type"]).toBeUndefined();
  });

  it("url 헬퍼는 인코딩된 경로를 만든다", () => {
    expect(AttachmentApi.url("att-1")).toBe("/api/attachments/att-1");
  });

  it("list는 노트 첨부 목록을 GET한다", async () => {
    const rows = [{ id: "att-1", filename: "a.png", size: 3, mime: "image/png", image: true, url: "/api/attachments/att-1" }];
    const f = mockFetch(200, rows);
    vi.stubGlobal("fetch", f);
    const res = await AttachmentApi.list("n1");
    expect(f.mock.calls[0][0]).toBe("/api/nodes/n1/attachments");
    expect(res[0].image).toBe(true);
  });

  it("listShare는 토큰 스코프 목록을 GET한다", async () => {
    const f = mockFetch(200, []);
    vi.stubGlobal("fetch", f);
    await AttachmentApi.listShare("tok-1");
    expect(f.mock.calls[0][0]).toBe("/api/share/tok-1/attachments");
  });

  it("remove는 DELETE를 보낸다", async () => {
    const f = mockFetch(204, {});
    vi.stubGlobal("fetch", f);
    await AttachmentApi.remove("att-1");
    expect(f.mock.calls[0][0]).toBe("/api/attachments/att-1");
    expect(f.mock.calls[0][1].method).toBe("DELETE");
  });
});
