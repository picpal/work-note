import { describe, it, expect } from "vitest";
import { ancestorDenyBlocks, ancestorsOf, inheritedEntries, directPublicMode, effectivePublic } from "./aclView";
import type { ApiAclEntry, ApiAclRow, ApiPublicFlag } from "./api";

const tree = [
  { id: "f1", type: "folder", name: "A", children: [
    { id: "f2", type: "folder", name: "B", children: [
      { id: "n1", type: "note", title: "노트" },
    ]},
  ]},
];

const acl: ApiAclRow[] = [
  { nodeId: "f1", principalType: "team", principalId: "t1", grantType: "read" },
  { nodeId: "f2", principalType: "user", principalId: "u1", grantType: "deny" },
  { nodeId: "n1", principalType: "user", principalId: "u2", grantType: "edit" },
];

describe("aclView", () => {
  it("ancestorsOf는 가까운 조상부터", () => {
    expect(ancestorsOf("n1", tree as never)).toEqual(["f2", "f1"]);
    expect(ancestorsOf("f1", tree as never)).toEqual([]);
  });
  it("inheritedEntries는 조상의 엔트리를 출처와 함께 (직접 엔트리 제외)", () => {
    const inh = inheritedEntries("n1", tree as never, acl);
    expect(inh).toHaveLength(2);
    expect(inh[0]).toMatchObject({ fromNodeId: "f2", grantType: "deny" });
    expect(inh[1]).toMatchObject({ fromNodeId: "f1", grantType: "read" });
  });
  it("directPublicMode / effectivePublic — nearest flag 의미론", () => {
    const flags: ApiPublicFlag[] = [{ nodeId: "f1", mode: "public" }, { nodeId: "n1", mode: "exclude" }];
    expect(directPublicMode("f1", flags)).toBe("public");
    expect(directPublicMode("f2", flags)).toBeNull();
    expect(effectivePublic("f2", tree as never, flags)).toBe(true);
    expect(effectivePublic("n1", tree as never, flags)).toBe(false);
  });
});

describe("ancestorDenyBlocks — deny-sticky 충돌 판정(스펙 §5.1)", () => {
  const inh = (rows: Array<[string, ApiAclEntry["principalType"], string, ApiAclEntry["grantType"]]>) =>
    rows.map(([fromNodeId, principalType, principalId, grantType]) => ({
      fromNodeId, nodeId: fromNodeId, principalType, principalId, grantType,
    }));
  const row = (
    principalType: ApiAclEntry["principalType"], principalId: string, grantType: ApiAclEntry["grantType"],
  ): ApiAclEntry => ({ principalType, principalId, grantType });

  it("QA 재현: 부모에 같은 주체 거부가 있으면 자식의 편집은 무효", () => {
    const blocks = ancestorDenyBlocks(
      [row("user", "S2026-9001", "edit")],
      inh([["f2", "user", "S2026-9001", "deny"]]));
    expect(blocks).toEqual([{ index: 0, kind: "same", fromNodeId: "f2" }]);
  });

  it("가까운 조상의 deny를 출처로 짚는다", () => {
    const blocks = ancestorDenyBlocks(
      [row("team", "t1", "read")],
      inh([["f2", "team", "t1", "deny"], ["f1", "team", "t1", "deny"]]));
    expect(blocks[0].fromNodeId).toBe("f2");
  });

  it("조상 allow는 막지 않는다 — deny만 sticky", () => {
    expect(ancestorDenyBlocks([row("user", "u1", "edit")], inh([["f1", "user", "u1", "read"]]))).toEqual([]);
  });

  it("다른 주체의 deny는 (팀 소속을 모르므로) 무효로 단정하지 않는다", () => {
    expect(ancestorDenyBlocks([row("user", "u1", "edit")], inh([["f1", "team", "t1", "deny"]]))).toEqual([]);
    expect(ancestorDenyBlocks([row("user", "u1", "edit")], inh([["f1", "user", "u2", "deny"]]))).toEqual([]);
  });

  it("조상의 @all 거부는 모든 주체의 allow를 막는다(deny-우선 합집합)", () => {
    const blocks = ancestorDenyBlocks(
      [row("user", "u1", "edit"), row("team", "t1", "read")],
      inh([["f1", "all", "@all", "deny"]]));
    expect(blocks).toEqual([
      { index: 0, kind: "all", fromNodeId: "f1" },
      { index: 1, kind: "all", fromNodeId: "f1" },
    ]);
  });

  it("같은 주체 deny가 있으면 @all보다 그쪽을 사유로 (더 정확한 안내)", () => {
    const blocks = ancestorDenyBlocks(
      [row("user", "u1", "edit")],
      inh([["f2", "user", "u1", "deny"], ["f1", "all", "@all", "deny"]]));
    expect(blocks).toEqual([{ index: 0, kind: "same", fromNodeId: "f2" }]);
  });

  it("draft가 deny면 충돌이 아니고, 주체 미선택 행은 판정하지 않는다", () => {
    expect(ancestorDenyBlocks([row("user", "u1", "deny")], inh([["f1", "user", "u1", "deny"]]))).toEqual([]);
    expect(ancestorDenyBlocks([row("user", "", "edit")], inh([["f1", "all", "@all", "deny"]]))).toEqual([]);
  });

  it("상속이 없으면 아무것도 막히지 않는다", () => {
    expect(ancestorDenyBlocks([row("user", "u1", "edit")], [])).toEqual([]);
  });

  it("인덱스는 draft 위치를 그대로 가리킨다(행 표시용)", () => {
    const blocks = ancestorDenyBlocks(
      [row("user", "u1", "read"), row("user", "u2", "edit"), row("user", "u3", "edit")],
      inh([["f1", "user", "u3", "deny"]]));
    expect(blocks).toEqual([{ index: 2, kind: "same", fromNodeId: "f1" }]);
  });
});
