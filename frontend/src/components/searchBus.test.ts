import { describe, it, expect } from "vitest";
import { requestSearch, consumeSearchSeed, onSearchRequest } from "./searchBus";

describe("searchBus", () => {
  it("요청한 질의를 시드로 넘긴다", () => {
    requestSearch("#qa2tag");
    expect(consumeSearchSeed()).toBe("#qa2tag");
  });

  it("시드는 1회만 소비된다", () => {
    requestSearch("#qa2tag");
    consumeSearchSeed();
    expect(consumeSearchSeed()).toBe("");
  });

  it("요청이 없으면 빈 문자열", () => {
    consumeSearchSeed();
    expect(consumeSearchSeed()).toBe("");
  });

  it("마지막 요청이 이전 시드를 덮어쓴다", () => {
    requestSearch("#a");
    requestSearch("#b");
    expect(consumeSearchSeed()).toBe("#b");
  });

  it("window 없는 환경(node)에서도 구독은 no-op 해제자를 준다", () => {
    const off = onSearchRequest(() => {});
    expect(typeof off).toBe("function");
    off();
  });
});
