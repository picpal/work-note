import { describe, it, expect, beforeEach, vi } from "vitest";
import { LocalStorageRepository } from "./LocalStorageRepository";
import type { VaultTree } from "../types";

const mem = new Map<string, string>();
beforeEach(() => {
  mem.clear();
  vi.stubGlobal("localStorage", {
    getItem: (k: string) => mem.get(k) ?? null,
    setItem: (k: string, v: string) => { mem.set(k, v); },
  });
});

describe("LocalStorageRepository", () => {
  const tree: VaultTree = [{ id: "n1", type: "note", title: "t", tags: [], updated: "", content: "" }];
  it("round-trips a tree", async () => {
    const repo = new LocalStorageRepository("k");
    await repo.save(tree);
    expect(await repo.load()).toEqual(tree);
  });
  it("returns null when empty", async () => {
    expect(await new LocalStorageRepository("none").load()).toBeNull();
  });
  it("saveSync persists synchronously", () => {
    const repo = new LocalStorageRepository("k2");
    repo.saveSync(tree);
    expect(mem.has("k2")).toBe(true);
  });
});
