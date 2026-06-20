import { describe, it, expect } from "vitest";
import { searchMembers, MEMBER_RESULT_LIMIT } from "./memberSearch";
import type { ApiUserBase } from "./api";

/* 팀 멤버 추가를 select → 이름/사번 검색으로 전환.
   사람이 많아지면 select에서 찾기 힘든 문제를 클라 필터로 해결한다. */
const u = (id: string, emp: string, name: string): ApiUserBase => ({
  id, emp, name, email: null, roleId: "r-user", status: "active", lastLogin: null,
});

const pool: ApiUserBase[] = [
  u("u1", "E1001", "김철수"),
  u("u2", "E1002", "이영희"),
  u("u3", "A2003", "박철민"),
];

describe("searchMembers — 이름/사번 검색", () => {
  it("쿼리가 비면 전체 반환", () => {
    expect(searchMembers(pool, "").matches).toHaveLength(3);
    expect(searchMembers(pool, "   ").matches).toHaveLength(3);
  });
  it("사번 부분일치(대소문자 무시)", () => {
    expect(searchMembers(pool, "e100").matches.map((m) => m.id)).toEqual(["u1", "u2"]);
    expect(searchMembers(pool, "A2003").matches.map((m) => m.id)).toEqual(["u3"]);
  });
  it("이름 부분일치", () => {
    expect(searchMembers(pool, "철").matches.map((m) => m.id)).toEqual(["u1", "u3"]);
    expect(searchMembers(pool, "영희").matches.map((m) => m.id)).toEqual(["u2"]);
  });
  it("매칭 없으면 빈 배열", () => {
    expect(searchMembers(pool, "없는사람").matches).toEqual([]);
  });
  it("결과가 limit를 넘으면 shown은 잘리고 truncated=true", () => {
    const many = Array.from({ length: MEMBER_RESULT_LIMIT + 5 }, (_, i) =>
      u("m" + i, "E" + i, "사용자" + i));
    const r = searchMembers(many, "");
    expect(r.matches).toHaveLength(MEMBER_RESULT_LIMIT + 5);
    expect(r.shown).toHaveLength(MEMBER_RESULT_LIMIT);
    expect(r.truncated).toBe(true);
  });
  it("limit 이하면 truncated=false", () => {
    expect(searchMembers(pool, "").truncated).toBe(false);
  });
});
