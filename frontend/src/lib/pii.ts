import type { NotePii } from "../types";

/** 경고를 띄워야 하는 상태(미해결). exempted/none/없음은 비경고. */
export function piiWarns(pii: NotePii | null | undefined): boolean {
  return !!pii && (pii.status === "suspected" || pii.status === "requested" || pii.status === "rejected");
}

const LABELS: Record<string, string> = {
  rrn: "주민등록번호", phone: "휴대폰번호", email: "이메일", card: "신용카드번호",
  biz: "사업자등록번호", passport: "여권번호", driver: "운전면허번호",
};
export function piiTypeLabel(code: string): string {
  return LABELS[code] ?? code;
}

const STATUS_LABELS: Record<string, string> = {
  suspected: "탐지됨", requested: "검토 중", exempted: "허용됨", rejected: "반려됨", none: "없음",
};
export function piiStatusLabel(code: string): string {
  return STATUS_LABELS[code] ?? code;
}

/** 서버 ApiPiiMatch와 동형 — 뷰어 순수 로직용. */
export interface PiiMatch { type: string; line: number; col: number; value: string }
export interface Seg { text: string; mark: boolean }

/** 매치를 0-based 라인 인덱스별로 그룹화(line은 1-based → -1). */
export function matchesByLine(matches: PiiMatch[]): Map<number, PiiMatch[]> {
  const m = new Map<number, PiiMatch[]>();
  for (const x of matches) {
    const idx = x.line - 1;
    const arr = m.get(idx);
    if (arr) arr.push(x); else m.set(idx, [x]);
  }
  return m;
}

/** 한 라인을 매치 기준으로 [평문, 강조, 평문…] 세그먼트로 분할. */
export function splitLineSegments(lineText: string, lineMatches: PiiMatch[]): Seg[] {
  if (lineMatches.length === 0) return [{ text: lineText, mark: false }];
  const sorted = [...lineMatches].sort((a, b) => a.col - b.col);
  const segs: Seg[] = [];
  let pos = 0;
  for (const m of sorted) {
    const start = Math.max(pos, m.col);
    if (start > pos) segs.push({ text: lineText.slice(pos, start), mark: false });
    const end = start + m.value.length;
    segs.push({ text: lineText.slice(start, end), mark: true });
    pos = end;
  }
  if (pos < lineText.length) segs.push({ text: lineText.slice(pos), mark: false });
  return segs;
}

/** 다음/이전 매치 인덱스(wrap-around). total≤0 → -1. */
export function nextMatchIndex(cur: number, total: number, dir: 1 | -1): number {
  if (total <= 0) return -1;
  return (cur + dir + total) % total;
}

/** 가상 윈도잉 가시 범위 [start, end). overscan 라인 여유. */
export function visibleRange(
  scrollTop: number, viewportH: number, row: number, total: number, overscan = 4,
): { start: number; end: number } {
  const start = Math.max(0, Math.floor(scrollTop / row) - overscan);
  const end = Math.min(total, Math.ceil((scrollTop + viewportH) / row) + overscan);
  return { start, end };
}
