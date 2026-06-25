import { describe, it, expect } from "vitest";
import { splitDirection, SPLIT_THRESHOLD } from "./redmineSplit";

describe("splitDirection", () => {
  it("임계폭 이상이면 좌우(row)", () => {
    expect(splitDirection(SPLIT_THRESHOLD)).toBe("row");
    expect(splitDirection(SPLIT_THRESHOLD + 1)).toBe("row");
  });
  it("임계폭 미만이면 상하(column)", () => {
    expect(splitDirection(SPLIT_THRESHOLD - 1)).toBe("column");
    expect(splitDirection(320)).toBe("column");
  });
});
