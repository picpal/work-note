import { describe, it, expect } from "vitest";
import { buildBacklinks } from "./linkIndex";
import type { VaultTree } from "../types";

function note(id: string, title: string, content = ""): any {
  return { id, type: "note", title, tags: [], updated: "", content };
}
function folder(id: string, name: string, children: any[]): any {
  return { id, type: "folder", name, children };
}

describe("buildBacklinks", () => {
  it("대상 id별로 출발 노트를 모은다", () => {
    const tree: VaultTree = [
      note("a", "A", "[[id:c|C 참조]]"),
      note("b", "B", "본문 [[id:c]]"),
      note("c", "C", "끝"),
    ];
    const idx = buildBacklinks(tree);
    expect(idx.get("c")).toEqual([
      { sourceId: "a", sourceTitle: "A" },
      { sourceId: "b", sourceTitle: "B" },
    ]);
    expect(idx.get("a")).toBeUndefined();
  });

  it("폴더 안 노트도 순회한다", () => {
    const tree: VaultTree = [folder("f", "F", [note("a", "A", "[[id:b]]"), note("b", "B")])];
    expect(buildBacklinks(tree).get("b")).toEqual([{ sourceId: "a", sourceTitle: "A" }]);
  });

  it("self-link는 제외한다", () => {
    const tree: VaultTree = [note("a", "A", "[[id:a|자기]]")];
    expect(buildBacklinks(tree).get("a")).toBeUndefined();
  });

  it("같은 노트가 같은 대상을 여러 번 가리켜도 1회만 집계한다", () => {
    const tree: VaultTree = [note("a", "A", "[[id:b]] 또 [[id:b|다시]]"), note("b", "B")];
    expect(buildBacklinks(tree).get("b")).toEqual([{ sourceId: "a", sourceTitle: "A" }]);
  });

  it("제목이 비면 '제목 없음'으로 표기한다", () => {
    const tree: VaultTree = [note("a", "", "[[id:b]]"), note("b", "B")];
    expect(buildBacklinks(tree).get("b")).toEqual([{ sourceId: "a", sourceTitle: "제목 없음" }]);
  });
});
