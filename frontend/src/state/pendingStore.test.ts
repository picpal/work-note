import { describe, it, expect, beforeEach, vi, afterEach } from "vitest";
import { savePending, clearPending, loadPending, clearAllPending } from "./pendingStore";

// node 환경 localStorage 스텁
function stubStorage() {
  const data = new Map<string, string>();
  vi.stubGlobal("localStorage", {
    getItem: (k: string) => (data.has(k) ? data.get(k)! : null),
    setItem: (k: string, v: string) => { data.set(k, v); },
    removeItem: (k: string) => { data.delete(k); },
  });
}

describe("pendingStore", () => {
  beforeEach(() => { stubStorage(); });
  afterEach(() => { vi.unstubAllGlobals(); });

  it("save 후 load 로 patch 회수", () => {
    savePending("n1", { title: "T", content: "C" });
    expect(loadPending()).toEqual({ n1: { title: "T", content: "C" } });
  });

  it("같은 id 재저장은 덮어쓴다", () => {
    savePending("n1", { content: "a" });
    savePending("n1", { content: "b", tags: ["x"] });
    expect(loadPending().n1).toEqual({ content: "b", tags: ["x"] });
  });

  it("여러 노트를 독립 보관", () => {
    savePending("n1", { content: "a" });
    savePending("n2", { content: "b" });
    expect(Object.keys(loadPending()).sort()).toEqual(["n1", "n2"]);
  });

  it("clearPending 은 해당 id만 제거", () => {
    savePending("n1", { content: "a" });
    savePending("n2", { content: "b" });
    clearPending("n1");
    expect(loadPending()).toEqual({ n2: { content: "b" } });
  });

  it("clearAllPending 은 전부 제거", () => {
    savePending("n1", { content: "a" });
    clearAllPending();
    expect(loadPending()).toEqual({});
  });

  it("손상된 JSON 은 빈 객체로 폴백", () => {
    localStorage.setItem("wn.pending.v1", "{not json");
    expect(loadPending()).toEqual({});
  });
});
