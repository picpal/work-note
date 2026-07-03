import { describe, it, expect } from "vitest";
import { pendingDiffers } from "./pendingRecovery";

describe("pendingDiffers — 스테일 미러 판별", () => {
  it("내용이 로드된 노트와 같으면 false (이미 서버 반영 → 복구 안 함)", () => {
    expect(pendingDiffers({ content: "hello" }, { content: "hello" })).toBe(false);
  });

  it("content가 다르면 true (진짜 미저장 편집 → 복구)", () => {
    expect(pendingDiffers({ content: "old" }, { content: "new" })).toBe(true);
  });

  it("title이 다르면 true", () => {
    expect(pendingDiffers({ title: "A", content: "x" }, { title: "B", content: "x" })).toBe(true);
  });

  it("tags 순서/값 같으면 false, 다르면 true", () => {
    expect(pendingDiffers({ tags: ["a", "b"] }, { tags: ["a", "b"] })).toBe(false);
    expect(pendingDiffers({ tags: ["a"] }, { tags: ["a", "b"] })).toBe(true);
    expect(pendingDiffers({ tags: ["a", "b"] }, { tags: ["b", "a"] })).toBe(true);
  });

  it("patch에 없는 필드는 비교 대상 아님 (title만 담긴 patch는 content 무시)", () => {
    expect(pendingDiffers({ title: "same", content: "whatever" }, { title: "same" })).toBe(false);
  });

  it("타깃 필드 누락은 빈 값으로 간주 — 빈 patch content vs 없는 노트 content는 같음", () => {
    expect(pendingDiffers({}, { content: "" })).toBe(false);
    expect(pendingDiffers({}, { content: "x" })).toBe(true);
    expect(pendingDiffers({}, { tags: [] })).toBe(false);
  });
});
