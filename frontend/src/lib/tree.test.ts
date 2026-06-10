import { describe, it, expect } from "vitest";
import { walkTree, findNode, updateNode, insertChild, removeNode, flattenNotes, countNotes, dedupeIds } from "./tree";
import type { VaultTree, FolderNode, NoteNode } from "../types";

const note = (id: string, title: string): NoteNode => ({ id, type: "note", title, tags: [], updated: "2026-06-10", content: "" });
const folder = (id: string, name: string, children: VaultTree = []): FolderNode => ({ id, type: "folder", name, open: true, children });
const make = (): VaultTree => [folder("f1", "A", [note("n1", "one"), folder("f2", "B", [note("n2", "two")])]), note("n3", "root")];

describe("tree", () => {
  it("findNode returns node, parent and path", () => {
    const t = make();
    const { node, parentNode, path } = findNode(t, "n2");
    expect(node?.id).toBe("n2");
    expect(parentNode?.id).toBe("f2");
    expect(path).toEqual(["A", "B"]);
  });
  it("findNode misses → node null", () => {
    expect(findNode(make(), "zz").node).toBeNull();
  });
  it("updateNode returns new tree, original untouched", () => {
    const t = make();
    const t2 = updateNode(t, "n1", (n) => { (n as NoteNode).title = "ONE"; });
    expect((findNode(t2, "n1").node as NoteNode).title).toBe("ONE");
    expect((findNode(t, "n1").node as NoteNode).title).toBe("one");
  });
  it("insertChild into folder opens it and appends", () => {
    const t2 = insertChild(make(), "f2", note("n4", "four"));
    const f2 = findNode(t2, "f2").node as FolderNode;
    expect(f2.open).toBe(true);
    expect(f2.children.map((c) => c.id)).toContain("n4");
  });
  it("insertChild with null folderId appends to root", () => {
    const t2 = insertChild(make(), null, note("n4", "four"));
    expect(t2[t2.length - 1].id).toBe("n4");
  });
  it("removeNode removes nested node", () => {
    const t2 = removeNode(make(), "n2");
    expect(findNode(t2, "n2").node).toBeNull();
  });
  it("flattenNotes returns notes with folder paths", () => {
    const flat = flattenNotes(make());
    expect(flat.map((f) => f.note.id).sort()).toEqual(["n1", "n2", "n3"]);
    expect(flat.find((f) => f.note.id === "n2")?.path).toEqual(["A", "B"]);
  });
  it("countNotes counts recursively", () => {
    expect(countNotes(make()[0] as FolderNode)).toBe(2);
  });
  it("dedupeIds reassigns duplicates", () => {
    const t: VaultTree = [note("x", "a"), note("x", "b")];
    dedupeIds(t);
    expect(t[0].id).not.toBe(t[1].id);
  });
  it("walkTree visits every node with depth", () => {
    const seen: Array<[string, number]> = [];
    walkTree(make(), (n, _p, d) => seen.push([n.id, d]));
    expect(seen).toContainEqual(["n2", 2]);
  });
});
