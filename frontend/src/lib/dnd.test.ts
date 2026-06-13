import { describe, it, expect } from "vitest";
import { canDropOn } from "./dnd";
import type { VaultTree } from "../types";

const tree: VaultTree = [
  { id: "f1", type: "folder", name: "F1", children: [
    { id: "n1", type: "note", title: "N1", tags: [], updated: "2026-06-13", content: "" },
    { id: "f2", type: "folder", name: "F2", children: [
      { id: "n2", type: "note", title: "N2", tags: [], updated: "2026-06-13", content: "" },
    ] },
  ] },
  { id: "f3", type: "folder", name: "F3", children: [] },
  { id: "n3", type: "note", title: "N3", tags: [], updated: "2026-06-13", content: "" },
];

describe("canDropOn", () => {
  it("노트를 다른 폴더로 드롭 허용", () => { expect(canDropOn(tree, "n3", "f1")).toBe(true); });
  it("폴더를 다른 폴더로 드롭 허용", () => { expect(canDropOn(tree, "f3", "f1")).toBe(true); });
  it("노트 위로는 드롭 불가", () => { expect(canDropOn(tree, "n3", "n1")).toBe(false); });
  it("자기 자신 위로 불가", () => { expect(canDropOn(tree, "f1", "f1")).toBe(false); });
  it("자손 폴더로 불가", () => { expect(canDropOn(tree, "f1", "f2")).toBe(false); });
  it("이미 그 부모면 무변경(불가)", () => { expect(canDropOn(tree, "n1", "f1")).toBe(false); });
  it("중첩 노드를 루트로 드롭 허용", () => { expect(canDropOn(tree, "n1", null)).toBe(true); });
  it("이미 루트면 루트로 불가", () => { expect(canDropOn(tree, "f3", null)).toBe(false); });
  it("존재하지 않는 dragged 불가", () => { expect(canDropOn(tree, "zzz", "f1")).toBe(false); });
});
