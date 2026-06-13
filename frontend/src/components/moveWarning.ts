/* moveWarning — 이동 미리보기를 사용자 경고 문구로 변환하는 순수 판정 로직. */
import type { MovePreview } from "../storage/VaultApi";

export interface WarnResult {
  warn: boolean;
  strong: boolean;
  lines: string[];
}

/** 노출 확대(공개 노출 시작·다른 팀 스페이스로 이동)는 strong(위험) 처리. */
export function shouldWarn(p: MovePreview): WarnResult {
  const lines: string[] = [];
  let strong = false;
  if (p.publicAfter && !p.publicBefore) {
    lines.push("이 위치에서는 전 직원이 읽을 수 있게 됩니다 (공개 노출).");
    strong = true;
  }
  if (!p.publicAfter && p.publicBefore) {
    lines.push("더 이상 공개 노출되지 않습니다.");
  }
  if (p.crossSpace) {
    lines.push("다른 팀 스페이스로 이동합니다 (" + (p.fromSpace ?? "공용") + " → " + (p.toSpace ?? "공용") + ").");
    strong = true;
  }
  if (p.added.length) lines.push("새로 접근 가능: " + p.added.join(", ") + ".");
  if (p.removed.length) lines.push("접근 해제: " + p.removed.join(", ") + ".");
  return { warn: lines.length > 0, strong, lines };
}
