import { describe, it, expect } from "vitest";
import { parseLinks, makeLink, WIKILINK_RE } from "./wikilink";

describe("parseLinks", () => {
  it("라벨 있는 단일 링크를 파싱한다", () => {
    const r = parseLinks("앞 [[id:abc|배포 런북]] 뒤");
    expect(r).toEqual([{ id: "abc", label: "배포 런북", from: 2, to: 18 }]);
  });

  it("라벨 없는 링크는 label이 빈 문자열", () => {
    const r = parseLinks("[[id:n1]]");
    expect(r).toEqual([{ id: "n1", label: "", from: 0, to: 9 }]);
  });

  it("여러 링크를 순서대로 파싱한다", () => {
    const r = parseLinks("[[id:a|A]] 그리고 [[id:b|B]]");
    expect(r.map((x) => x.id)).toEqual(["a", "b"]);
  });

  it("깨진/비링크 대괄호는 무시한다", () => {
    expect(parseLinks("[[잘못]] [단일](x) [[id: ]]")).toEqual([]);
  });

  it("id 안에 공백·파이프·닫는 대괄호는 허용하지 않는다", () => {
    expect(parseLinks("[[id:a b|X]]")).toEqual([]);
  });
});

describe("makeLink", () => {
  it("라벨이 있으면 id:라벨 형식", () => {
    expect(makeLink("abc", "배포 런북")).toBe("[[id:abc|배포 런북]]");
  });
  it("라벨이 비면 id만", () => {
    expect(makeLink("abc", "")).toBe("[[id:abc]]");
  });
});

describe("WIKILINK_RE", () => {
  it("전역 플래그를 가진다(반복 exec)", () => {
    expect(WIKILINK_RE.global).toBe(true);
  });
});
