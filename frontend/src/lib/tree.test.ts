import { describe, it, expect } from "vitest";
import { walkTree, findNode, updateNode, insertChild, removeNode, flattenNotes, countNotes, dedupeIds, moveNode, isSelfOrDescendant, folderOptions } from "./tree";
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
  it("insertChild opens a closed folder", () => {
    const t: VaultTree = [{ id: "fc", type: "folder", name: "C", open: false, children: [] }];
    const t2 = insertChild(t, "fc", note("n9", "nine"));
    expect((findNode(t2, "fc").node as FolderNode).open).toBe(true);
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
  it("dedupeIds reassigns duplicates in nested folders", () => {
    const t: VaultTree = [folder("f1", "A", [note("x", "a"), note("x", "b")])];
    dedupeIds(t);
    const kids = (t[0] as FolderNode).children;
    expect(kids[0].id).not.toBe(kids[1].id);
  });
  it("walkTree visits every node with depth", () => {
    const seen: Array<[string, number]> = [];
    walkTree(make(), (n, _p, d) => seen.push([n.id, d]));
    expect(seen).toContainEqual(["n2", 2]);
  });
});

describe("isSelfOrDescendant", () => {
  it("self → true", () => {
    expect(isSelfOrDescendant(make(), "f1", "f1")).toBe(true);
  });
  it("direct child → true", () => {
    expect(isSelfOrDescendant(make(), "f1", "n1")).toBe(true);
  });
  it("grandchild → true", () => {
    expect(isSelfOrDescendant(make(), "f1", "n2")).toBe(true);
  });
  it("unrelated node → false", () => {
    expect(isSelfOrDescendant(make(), "f2", "n1")).toBe(false);
  });
  it("note (no children) → false for any other id", () => {
    expect(isSelfOrDescendant(make(), "n3", "n1")).toBe(false);
  });
});

describe("moveNode", () => {
  it("moves a note into a folder (gone from origin, present in target)", () => {
    const t2 = moveNode(make(), "n3", "f2");
    expect(findNode(t2, "n3").parentNode?.id).toBe("f2");
    expect(t2.some((n) => n.id === "n3")).toBe(false); // no longer at root
  });
  it("moves a folder into another folder", () => {
    const t2 = moveNode(make(), "f2", null); // first pull B to root, then back under A
    const t3 = moveNode(t2, "f2", "f1");
    expect(findNode(t3, "f2").parentNode?.id).toBe("f1");
  });
  it("moves to root (parentId=null → top level)", () => {
    const t2 = moveNode(make(), "n2", null);
    expect(t2.some((n) => n.id === "n2")).toBe(true);
    expect(findNode(t2, "n2").parentNode).toBeNull();
  });
  it("rejects move into self (unchanged, same reference)", () => {
    const t = make();
    expect(moveNode(t, "f1", "f1")).toBe(t);
  });
  it("rejects move into own descendant (unchanged)", () => {
    const t = make();
    expect(moveNode(t, "f1", "f2")).toBe(t); // f2 is inside f1
  });
  it("missing id → unchanged", () => {
    const t = make();
    expect(moveNode(t, "zz", "f1")).toBe(t);
  });
});

describe("folderOptions", () => {
  it("includes all folders, excludes notes", () => {
    const opts = folderOptions(make(), "n3");
    expect(opts.map((o) => o.id).sort()).toEqual(["f1", "f2"]);
  });
  it("excludes the excludeId folder and its descendant folders", () => {
    const opts = folderOptions(make(), "f1");
    expect(opts.map((o) => o.id)).toEqual([]); // f1 self + f2 descendant both excluded
  });
  it("path label format (A / B)", () => {
    const opts = folderOptions(make(), "n3");
    expect(opts.find((o) => o.id === "f2")?.label).toBe("A / B");
    expect(opts.find((o) => o.id === "f1")?.label).toBe("A");
  });
  it("does not include root (caller adds it)", () => {
    const opts = folderOptions(make(), "n3");
    expect(opts.some((o) => o.id === null as unknown as string)).toBe(false);
  });
});

describe("structural sharing", () => {
  it("updateNode preserves untouched sibling references", () => {
    const t = make();
    const t2 = updateNode(t, "n1", (n) => { (n as NoteNode).title = "ONE"; });
    expect(t2[1]).toBe(t[1]);                                  // 건드리지 않은 루트 노트: 같은 참조
    const f2old = ((t[0] as FolderNode).children[1]) as FolderNode;
    const f2new = ((t2[0] as FolderNode).children[1]) as FolderNode;
    expect(f2new).toBe(f2old);                                  // 형제 폴더 서브트리: 같은 참조
    expect(t2[0]).not.toBe(t[0]);                               // 변경 경로: 새 객체
  });
  it("insertChild preserves sibling references", () => {
    const t = make();
    const t2 = insertChild(t, "f2", note("n9", "nine"));
    expect(t2[1]).toBe(t[1]);
  });
  it("removeNode preserves sibling references", () => {
    const t = make();
    const t2 = removeNode(t, "n2");
    expect(t2[1]).toBe(t[1]);
  });
});
