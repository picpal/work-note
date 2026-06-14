import { describe, it, expect } from "vitest";
import { vaultReducer, type VaultAction } from "./vaultReducer";
import type { VaultTree, FolderNode, NoteNode } from "../types";

const base = (): VaultTree => [
  { id: "f1", type: "folder", name: "A", open: true, children: [{ id: "n1", type: "note", title: "one", tags: [], updated: "2026-01-01", content: "x" }] },
];

describe("vaultReducer", () => {
  it("toggle flips folder open", () => {
    const t = vaultReducer(base(), { type: "toggle", id: "f1" });
    expect((t[0] as FolderNode).open).toBe(false);
  });
  it("rename sets folder name / note title", () => {
    let t = vaultReducer(base(), { type: "rename", id: "f1", value: "B" });
    expect((t[0] as FolderNode).name).toBe("B");
    t = vaultReducer(t, { type: "rename", id: "n1", value: "ONE" });
    expect(((t[0] as FolderNode).children[0] as NoteNode).title).toBe("ONE");
  });
  it("updateNote patches and stamps updated", () => {
    const t = vaultReducer(base(), { type: "updateNote", id: "n1", patch: { content: "y" } });
    const n = (t[0] as FolderNode).children[0] as NoteNode;
    expect(n.content).toBe("y");
    expect(n.updated).toMatch(/^\d{4}-\d{2}-\d{2}$/);
  });
  it("insert/remove round-trip", () => {
    let t = vaultReducer(base(), { type: "insert", folderId: "f1", node: { id: "n2", type: "note", title: "t", tags: [], updated: "", content: "" } });
    expect((t[0] as FolderNode).children).toHaveLength(2);
    t = vaultReducer(t, { type: "remove", id: "n2" });
    expect((t[0] as FolderNode).children).toHaveLength(1);
  });
  it("collapseAll closes every folder", () => {
    const t = vaultReducer(base(), { type: "collapseAll" });
    expect((t[0] as FolderNode).open).toBe(false);
  });
  it("move relocates a node to a new parent", () => {
    const t = vaultReducer(base(), { type: "move", id: "n1", parentId: null });
    expect((t[0] as FolderNode).children).toHaveLength(0); // gone from f1
    expect(t[t.length - 1].id).toBe("n1"); // now at root
  });
  it("move into own descendant is a no-op", () => {
    const tree: VaultTree = [
      { id: "f1", type: "folder", name: "A", open: true, children: [
        { id: "f2", type: "folder", name: "B", open: true, children: [] },
      ] },
    ];
    const t = vaultReducer(tree, { type: "move", id: "f1", parentId: "f2" });
    expect(t).toBe(tree); // unchanged
  });
});

describe("setNotePii", () => {
  it("pii만 설정하고 updated는 건드리지 않는다", () => {
    const flat: VaultTree = [{ id: "n1", type: "note", title: "T", tags: [], updated: "2026-01-01", content: "c" }];
    const out = vaultReducer(flat, { type: "setNotePii", id: "n1", pii: { status: "suspected", types: ["phone"] } });
    const note = out[0] as NoteNode;
    expect(note.pii?.status).toBe("suspected");
    expect(note.updated).toBe("2026-01-01");
  });
});
