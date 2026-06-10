import { describe, it, expect } from "vitest";
import { newId } from "./id";

describe("newId", () => {
  it("generates unique ids", () => {
    const ids = new Set(Array.from({ length: 1000 }, () => newId()));
    expect(ids.size).toBe(1000);
  });
  it("starts with u", () => {
    expect(newId()).toMatch(/^u/);
  });
});
