import { describe, it, expect } from "vitest";
import { matchesByLine, splitLineSegments, nextMatchIndex, visibleRange, type PiiMatch } from "./pii";

const mt = (over: Partial<PiiMatch>): PiiMatch =>
  ({ type: "phone", line: 1, col: 0, value: "010-1234-5678", ...over });

describe("matchesByLine", () => {
  it("1-based line을 0-based 인덱스로 그룹화", () => {
    const m = matchesByLine([mt({ line: 2 }), mt({ line: 2, col: 20 }), mt({ line: 5 })]);
    expect(m.get(1)!.length).toBe(2);
    expect(m.get(4)!.length).toBe(1);
  });
});

describe("splitLineSegments", () => {
  it("매치 없으면 전체 평문", () => {
    expect(splitLineSegments("hello", [])).toEqual([{ text: "hello", mark: false }]);
  });
  it("단일 매치 분할", () => {
    const segs = splitLineSegments("전화 010-1234-5678 끝", [mt({ col: 3 })]);
    expect(segs).toEqual([
      { text: "전화 ", mark: false },
      { text: "010-1234-5678", mark: true },
      { text: " 끝", mark: false },
    ]);
  });
  it("같은 줄 다중 매치", () => {
    const segs = splitLineSegments("a@b.com 010-1234-5678", [
      mt({ type: "email", col: 0, value: "a@b.com" }), mt({ col: 8 }),
    ]);
    expect(segs.filter((s) => s.mark).map((s) => s.text)).toEqual(["a@b.com", "010-1234-5678"]);
  });
});

describe("nextMatchIndex", () => {
  it("wrap-around", () => {
    expect(nextMatchIndex(2, 3, 1)).toBe(0);
    expect(nextMatchIndex(0, 3, -1)).toBe(2);
    expect(nextMatchIndex(0, 0, 1)).toBe(-1);
  });
});

describe("visibleRange", () => {
  it("스크롤 위치 기준 가시 범위 + overscan", () => {
    expect(visibleRange(200, 100, 20, 1000, 2)).toEqual({ start: 8, end: 17 });
  });
});
