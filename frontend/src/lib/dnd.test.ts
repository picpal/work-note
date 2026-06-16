import { describe, it, expect } from "vitest";
import { canDropOn } from "./dnd";
import type { VaultTree } from "../types";

const tree: VaultTree = [
  { id: "f1", type: "folder", name: "F1", children: [
    { id: "n1", type: "note", title: "N1", tags: [], updated: "2026-06-13", content: "" },
    { id: "f2", type: "folder", name: "F2", children: [
      { id: "n2", type: "note", title: "N2", tags: [], updated: "2026-06-13", content: "" },
      { id: "f4", type: "folder", name: "F4", children: [] },
    ] },
  ] },
  { id: "f3", type: "folder", name: "F3", children: [] },
  { id: "n3", type: "note", title: "N3", tags: [], updated: "2026-06-13", content: "" },
];

describe("canDropOn", () => {
  it("최상위 노트를 폴더로 드롭 허용", () => { expect(canDropOn(tree, "n3", "f1")).toBe(true); });
  it("중첩 노트를 다른 폴더로 드롭 허용", () => { expect(canDropOn(tree, "n2", "f3")).toBe(true); });
  it("중첩 폴더를 다른 폴더로 드롭 허용", () => { expect(canDropOn(tree, "f2", "f3")).toBe(true); });
  it("최상위 폴더는 이동 불가(immovable)", () => { expect(canDropOn(tree, "f3", "f1")).toBe(false); });
  it("최상위 폴더는 어느 폴더로도 불가", () => { expect(canDropOn(tree, "f1", "f3")).toBe(false); });
  it("노트 위로는 드롭 불가", () => { expect(canDropOn(tree, "n3", "n1")).toBe(false); });
  it("자기 자신 위로 불가", () => { expect(canDropOn(tree, "f2", "f2")).toBe(false); });
  it("자손 폴더로 불가(이동 가능 소스)", () => { expect(canDropOn(tree, "f2", "f4")).toBe(false); });
  it("이미 그 부모면 무변경(불가)", () => { expect(canDropOn(tree, "n1", "f1")).toBe(false); });
  it("루트로는 드롭 불가(중첩 노트)", () => { expect(canDropOn(tree, "n1", null)).toBe(false); });
  it("루트로는 드롭 불가(최상위 노트)", () => { expect(canDropOn(tree, "n3", null)).toBe(false); });
  it("존재하지 않는 dragged 불가", () => { expect(canDropOn(tree, "zzz", "f1")).toBe(false); });
});
