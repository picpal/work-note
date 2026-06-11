import { describe, it, expect, vi } from "vitest";
import { syncAction, treeToCreateOps } from "./useVaultSync";
import type { SyncOp } from "./useVaultSync";
import type { VaultApiType } from "../storage/VaultApi";
import type { VaultTree } from "../types";

const apiMock = () =>
  ({
    tree: vi.fn(),
    create: vi.fn().mockResolvedValue(undefined),
    update: vi.fn().mockResolvedValue(undefined),
    move: vi.fn().mockResolvedValue(undefined),
    trash: vi.fn().mockResolvedValue(undefined),
  }) as unknown as VaultApiType;

describe("syncAction", () => {
  it("maps create op to VaultApi.create", async () => {
    const api = apiMock();
    await syncAction(api, { kind: "create", node: { id: "n1", parentId: "f1", type: "note", name: "제목 없는 노트", content: "" } });
    expect(api.create).toHaveBeenCalledWith(expect.objectContaining({ id: "n1", parentId: "f1" }));
  });
  it("maps rename to update(name)", async () => {
    const api = apiMock();
    await syncAction(api, { kind: "rename", id: "n1", name: "새 이름" });
    expect(api.update).toHaveBeenCalledWith("n1", { name: "새 이름" });
  });
  it("maps update to update(content/tags)", async () => {
    const api = apiMock();
    await syncAction(api, { kind: "update", id: "n1", content: "본문", tags: ["운영"] });
    expect(api.update).toHaveBeenCalledWith("n1", { content: "본문", tags: ["운영"] });
  });
  it("maps content-only update without a tags key", async () => {
    const api = apiMock();
    await syncAction(api, { kind: "update", id: "n1", content: "본문만" });
    expect(api.update).toHaveBeenCalledWith("n1", { content: "본문만" });
  });
  it("maps remove to trash", async () => {
    const api = apiMock();
    await syncAction(api, { kind: "remove", id: "n1" });
    expect(api.trash).toHaveBeenCalledWith("n1");
  });
  it("maps move to move", async () => {
    const api = apiMock();
    await syncAction(api, { kind: "move", id: "n1", parentId: null });
    expect(api.move).toHaveBeenCalledWith("n1", null);
  });
});

describe("treeToCreateOps", () => {
  const tree: VaultTree = [
    {
      id: "f1", type: "folder", name: "A", children: [
        { id: "n1", type: "note", title: "노트1", tags: [], updated: "2026-06-10", content: "c1" },
        {
          id: "f2", type: "folder", name: "B", children: [
            { id: "n2", type: "note", title: "노트2", tags: [], updated: "2026-06-10", content: "c2" },
          ],
        },
      ],
    },
    { id: "n3", type: "note", title: "루트노트", tags: [], updated: "2026-06-10", content: "" },
  ];

  it("emits parents before children, siblings in array order", () => {
    const ops = treeToCreateOps(tree) as Array<Extract<SyncOp, { kind: "create" }>>;
    expect(ops.every((o) => o.kind === "create")).toBe(true);
    expect(ops.map((o) => o.node.id)).toEqual(["f1", "n1", "f2", "n2", "n3"]);
  });

  it("maps parentId, type, name and note content", () => {
    const ops = treeToCreateOps(tree) as Array<Extract<SyncOp, { kind: "create" }>>;
    const byId = Object.fromEntries(ops.map((o) => [o.node.id, o.node]));
    expect(byId["f1"]).toEqual({ id: "f1", parentId: null, type: "folder", name: "A" });
    expect(byId["n1"]).toEqual({ id: "n1", parentId: "f1", type: "note", name: "노트1", content: "c1" });
    expect(byId["n2"]).toEqual({ id: "n2", parentId: "f2", type: "note", name: "노트2", content: "c2" });
    expect(byId["n3"]).toEqual({ id: "n3", parentId: null, type: "note", name: "루트노트", content: "" });
  });
});
