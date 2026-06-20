/* 팀 멤버 추가 — 이름/사번 검색 필터.
   addables(추가 가능한 활성 비-멤버)는 클라이언트에 전부 로드돼 있어 백엔드 없이 즉시 필터한다.
   매칭: 사번(emp) 또는 이름(name) 부분일치(대소문자 무시). 쿼리 공백이면 전체.
   결과가 많으면 DOM 폭주를 막기 위해 표시는 limit개로 자르고 truncated 플래그로 안내한다. */
import type { ApiUserBase } from "./api";

export const MEMBER_RESULT_LIMIT = 50;

export interface MemberSearchResult {
  matches: ApiUserBase[];   // 전체 매칭
  shown: ApiUserBase[];     // 화면 표시(최대 limit)
  truncated: boolean;       // matches가 limit를 초과해 잘렸는지
}

export function searchMembers(
  addables: ApiUserBase[],
  query: string,
  limit: number = MEMBER_RESULT_LIMIT,
): MemberSearchResult {
  const q = query.trim().toLowerCase();
  const matches = q
    ? addables.filter((u) => u.emp.toLowerCase().includes(q) || u.name.toLowerCase().includes(q))
    : addables;
  return { matches, shown: matches.slice(0, limit), truncated: matches.length > limit };
}
