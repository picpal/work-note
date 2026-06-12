import { describe, it, expect } from "vitest";
import { ancestorsOf, inheritedEntries, directPublicMode, effectivePublic } from "./aclView";
import type { ApiAclRow, ApiPublicFlag } from "./api";

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
