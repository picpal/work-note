import { describe, it, expect } from "vitest";
import { moveTargets, filterMoveTargets, isCurrentTarget, ROOT_KEY } from "./moveTargets";
import type { VaultTree } from "../types";

const tree: VaultTree = [
  { id: "f1", type: "folder", name: "개발팀", children: [
    { id: "n1", type: "note", title: "N1", tags: [], updated: "2026-06-13", content: "" },
    { id: "f2", type: "folder", name: "회의록", children: [
      { id: "f4", type: "folder", name: "2026", children: [] },
    ] },
  ] },
  { id: "f3", type: "folder", name: "기획팀", children: [] },
  { id: "n3", type: "note", title: "N3", tags: [], updated: "2026-06-13", content: "" },
];

describe("moveTargets", () => {
  it("루트를 첫 항목으로 포함한다 (D-6)", () => {
    const list = moveTargets(tree, "n1");
    expect(list[0]).toEqual({ key: ROOT_KEY, parentId: null, label: "루트 (최상위)", kind: "root" });
  });

  it("루트 다음은 기존 폴더 후보(경로 라벨)", () => {
    const list = moveTargets(tree, "n1");
    expect(list.slice(1).map((t) => t.label)).toEqual(["개발팀", "개발팀 / 회의록", "개발팀 / 회의록 / 2026", "기획팀"]);
  });

  it("최상위 폴더는 space, 하위 폴더는 folder로 구분", () => {
    const list = moveTargets(tree, "n1");
    expect(list.find((t) => t.label === "개발팀")!.kind).toBe("space");
    expect(list.find((t) => t.label === "개발팀 / 회의록")!.kind).toBe("folder");
  });

  it("자신·자손 폴더는 제외하되 루트는 항상 남는다", () => {
    const list = moveTargets(tree, "f2");
    expect(list.map((t) => t.label)).toEqual(["루트 (최상위)", "개발팀", "기획팀"]);
  });
});

describe("filterMoveTargets", () => {
  const list = moveTargets(tree, "n1");

  it("빈 검색어는 전체", () => {
    expect(filterMoveTargets(list, "  ")).toHaveLength(list.length);
  });

  it("폴더 라벨 부분 일치", () => {
    expect(filterMoveTargets(list, "회의").map((t) => t.label)).toEqual(["개발팀 / 회의록", "개발팀 / 회의록 / 2026"]);
  });

  it("'루트'로 루트를 찾는다", () => {
    expect(filterMoveTargets(list, "루트").map((t) => t.key)).toEqual([ROOT_KEY]);
  });

  it("'최상위'·'root' 별칭으로도 루트를 찾는다", () => {
    for (const q of ["최상위", "root", "ROOT"]) {
      expect(filterMoveTargets(list, q).map((t) => t.key)).toEqual([ROOT_KEY]);
    }
  });

  it("'/'는 경로 구분자라 루트 별칭이 아니다(폴더 경로만 매칭)", () => {
    expect(filterMoveTargets(list, "/").every((t) => t.kind !== "root")).toBe(true);
  });

  it("일치 없으면 빈 목록", () => {
    expect(filterMoveTargets(list, "zzz")).toEqual([]);
  });
});

describe("isCurrentTarget", () => {
  const list = moveTargets(tree, "n3");
  const root = list[0];
  const dev = list.find((t) => t.label === "개발팀")!;

  it("최상위 노트에게 루트는 현재 위치", () => {
    expect(isCurrentTarget(root, null)).toBe(true);
  });

  it("폴더 안 노트에게 루트는 현재 위치가 아니다", () => {
    expect(isCurrentTarget(root, "f1")).toBe(false);
  });

  it("현재 부모 폴더는 현재 위치", () => {
    expect(isCurrentTarget(dev, "f1")).toBe(true);
  });
});
