import { describe, it, expect } from "vitest";
import { shouldWarn } from "./moveWarning";
import type { MovePreview } from "../storage/VaultApi";

function preview(p: Partial<MovePreview> = {}): MovePreview {
  return {
    publicBefore: false,
    publicAfter: false,
    crossSpace: false,
    fromSpace: null,
    toSpace: null,
    added: [],
    removed: [],
    ...p,
  };
}

describe("shouldWarn", () => {
  it("공개 노출 시작 → warn=true, strong=true, '공개 노출' 안내", () => {
    const r = shouldWarn(preview({ publicAfter: true, publicBefore: false }));
    expect(r.warn).toBe(true);
    expect(r.strong).toBe(true);
    expect(r.lines.some((l) => l.includes("공개 노출"))).toBe(true);
  });

  it("공개 해제 → warn=true, strong=false", () => {
    const r = shouldWarn(preview({ publicBefore: true, publicAfter: false }));
    expect(r.warn).toBe(true);
    expect(r.strong).toBe(false);
    expect(r.lines.some((l) => l.includes("더 이상 공개"))).toBe(true);
  });

  it("cross-space → warn=true, strong=true, from→to 라벨 포함", () => {
    const r = shouldWarn(preview({ crossSpace: true, fromSpace: "결제팀", toSpace: "운영팀" }));
    expect(r.warn).toBe(true);
    expect(r.strong).toBe(true);
    const line = r.lines.find((l) => l.includes("팀 스페이스"));
    expect(line).toBeDefined();
    expect(line).toContain("결제팀");
    expect(line).toContain("운영팀");
  });

  it("cross-space의 null 스페이스는 '공용'으로 표기", () => {
    const r = shouldWarn(preview({ crossSpace: true, fromSpace: null, toSpace: "운영팀" }));
    const line = r.lines.find((l) => l.includes("팀 스페이스"));
    expect(line).toContain("공용");
    expect(line).toContain("운영팀");
  });

  it("added만 → warn=true, strong=false, join 확인", () => {
    const r = shouldWarn(preview({ added: ["결제팀", "S2019-0007"] }));
    expect(r.warn).toBe(true);
    expect(r.strong).toBe(false);
    expect(r.lines.some((l) => l.includes("결제팀, S2019-0007"))).toBe(true);
  });

  it("removed만 → warn=true, strong=false, join 확인", () => {
    const r = shouldWarn(preview({ removed: ["운영팀", "S2019-0008"] }));
    expect(r.warn).toBe(true);
    expect(r.strong).toBe(false);
    expect(r.lines.some((l) => l.includes("운영팀, S2019-0008"))).toBe(true);
  });

  it("변경 없음 → warn=false, lines=[]", () => {
    const r = shouldWarn(preview());
    expect(r.warn).toBe(false);
    expect(r.strong).toBe(false);
    expect(r.lines).toEqual([]);
  });

  it("복합(공개 노출 + added) → lines 2줄, strong=true", () => {
    const r = shouldWarn(preview({ publicAfter: true, publicBefore: false, added: ["결제팀"] }));
    expect(r.warn).toBe(true);
    expect(r.strong).toBe(true);
    expect(r.lines.length).toBe(2);
  });
});
