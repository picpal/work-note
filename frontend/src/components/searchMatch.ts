/* searchMatch — 검색 질의 파싱 + 노트 매칭 판정(제목·본문·태그) 순수 로직.
   D-5: 태그가 입력·저장만 되고 검색으로 소비되지 않던 결함을 메우기 위해 태그를 1급 검색 대상으로 올린다.
   "#태그" 접두는 태그만 검색(태그 칩 클릭이 넣어주는 질의 형태). */

export type MatchField = "title" | "tag" | "body";

/** 매칭 대상 — 노트 타입에 의존하지 않도록 평문 3필드로 좁힌다(본문은 mdToText 결과). */
export interface SearchTarget {
  title: string;
  text: string;
  tags: string[];
}

export interface ParsedQuery {
  /** 소문자 정규화된 검색어. 빈 문자열이면 "질의 없음"(호출측은 목록 모드). */
  term: string;
  /** "#" 접두 → 태그만 검색. */
  tagOnly: boolean;
}

export interface MatchResult {
  score: number;
  /** 매칭된 필드 — 제목 → 태그 → 본문 순(표시 배지 순서와 동일). */
  fields: MatchField[];
  /** 매칭된 태그 원문(대소문자 보존) — 결과 행에 칩으로 표시. */
  tags: string[];
}

const SCORE: Record<MatchField, number> = { title: 4, tag: 2, body: 1 };
const EXACT_TAG_BONUS = 4;

export const FIELD_LABEL: Record<MatchField, string> = { title: "제목", tag: "태그", body: "본문" };

/** 태그 칩 클릭이 만드는 질의 문자열. parseSearchQuery가 tagOnly로 되돌린다. */
export function tagQuery(tag: string): string {
  return "#" + tag;
}

export function parseSearchQuery(raw: string): ParsedQuery {
  let s = (raw || "").trim();
  let tagOnly = false;
  if (s.startsWith("#")) {
    tagOnly = true;
    s = s.slice(1).trim();
  }
  // "#"만 친 상태는 아직 태그명이 없다 → 질의 없음으로 취급(전체 목록 유지)
  if (!s) return { term: "", tagOnly: false };
  return { term: s.toLowerCase(), tagOnly };
}

/** 매칭 판정. 질의가 비었거나 어느 필드도 안 맞으면 null. */
export function matchNote(t: SearchTarget, q: ParsedQuery): MatchResult | null {
  if (!q.term) return null;
  const tags = (t.tags || []).filter((x) => x.toLowerCase().includes(q.term));

  if (q.tagOnly) {
    if (tags.length === 0) return null;
    const exact = tags.some((x) => x.toLowerCase() === q.term);
    return { score: SCORE.tag + (exact ? EXACT_TAG_BONUS : 0), fields: ["tag"], tags };
  }

  const fields: MatchField[] = [];
  if ((t.title || "").toLowerCase().includes(q.term)) fields.push("title");
  if (tags.length > 0) fields.push("tag");
  if ((t.text || "").toLowerCase().includes(q.term)) fields.push("body");
  if (fields.length === 0) return null;

  // 정확 일치 보너스는 태그 전용 질의에만 — 일반 질의에서 태그가 제목을 역전하지 않도록.
  const score = fields.reduce((s, f) => s + SCORE[f], 0);
  return { score, fields, tags };
}

/** 매칭 + 점수 내림차순 정렬. 동점은 입력 순서 유지(안정 정렬). */
export function rankMatches<T>(list: T[], toTarget: (item: T) => SearchTarget, raw: string): Array<{ item: T; match: MatchResult }> {
  const q = parseSearchQuery(raw);
  if (!q.term) return [];
  const out: Array<{ item: T; match: MatchResult }> = [];
  for (const item of list) {
    const m = matchNote(toTarget(item), q);
    if (m) out.push({ item, match: m });
  }
  return out.sort((a, b) => b.match.score - a.match.score);
}
