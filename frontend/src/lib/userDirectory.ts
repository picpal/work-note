import type { DirectoryUser } from "../api/users";

/** 드롭다운/칩 표기 — "사번 이름". */
export function formatUser(u: DirectoryUser): string {
  return `${u.emp} ${u.name}`;
}

/** @검색 필터 — query(trim·소문자)를 emp 또는 name에 부분일치, 이미 선택된 emp 제외, 앞에서 limit개.
    빈 query면 제외 후 앞에서 limit개. 입력 배열은 이미 emp 정렬(백엔드)이라 정렬은 그대로 둔다. */
export function filterDirectory(
  all: DirectoryUser[],
  query: string,
  excludeEmps: string[],
  limit: number,
): DirectoryUser[] {
  const q = query.trim().toLowerCase();
  const exclude = new Set(excludeEmps);
  const out: DirectoryUser[] = [];
  for (const u of all) {
    if (exclude.has(u.emp)) continue;
    if (q && !u.emp.toLowerCase().includes(q) && !u.name.toLowerCase().includes(q)) continue;
    out.push(u);
    if (out.length >= limit) break;
  }
  return out;
}
