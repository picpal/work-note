import { describe, it, expect } from "vitest";
import { formatUser, filterDirectory } from "./userDirectory";
import type { DirectoryUser } from "../api/users";

const dir: DirectoryUser[] = [
  { emp: "10001", name: "홍길동" },
  { emp: "20002", name: "김철수" },
  { emp: "30015", name: "김영희" },
];

describe("formatUser", () => {
  it("사번 이름 형식", () => {
    expect(formatUser({ emp: "10001", name: "홍길동" })).toBe("10001 홍길동");
  });
});

describe("filterDirectory", () => {
  it("이름 부분일치", () => {
    expect(filterDirectory(dir, "김", [], 8).map((u) => u.emp)).toEqual(["20002", "30015"]);
  });
  it("사번 부분일치", () => {
    expect(filterDirectory(dir, "1000", [], 8).map((u) => u.emp)).toEqual(["10001"]);
  });
  it("대소문자 무시", () => {
    expect(filterDirectory([{ emp: "A100", name: "Bob" }], "bob", [], 8)).toHaveLength(1);
  });
  it("이미 선택된 emp 제외", () => {
    expect(filterDirectory(dir, "김", ["20002"], 8).map((u) => u.emp)).toEqual(["30015"]);
  });
  it("limit 적용", () => {
    expect(filterDirectory(dir, "", [], 2)).toHaveLength(2);
  });
  it("빈 query는 제외 후 전체", () => {
    expect(filterDirectory(dir, "", [], 8)).toHaveLength(3);
  });
});
