import { describe, it, expect } from "vitest";
import { roleMode, roleActionLabel, roleActionIcon } from "./roleActions";

/* 회귀: 시스템 역할 편집 버튼이 disabled(죽은 클릭=먹통)였던 것을 읽기전용 '보기'로 전환.
   액션 버튼은 더 이상 시스템 여부로 비활성화되지 않고, 모드만 view/edit로 갈린다. */
describe("roleMode — 역할 카드 액션 모드", () => {
  it("시스템 역할은 읽기전용 view (비활성 죽은 클릭 제거)", () => {
    expect(roleMode({ system: true })).toBe("view");
  });
  it("커스텀(비시스템) 역할은 edit", () => {
    expect(roleMode({ system: false })).toBe("edit");
  });
  it("라벨: view→'보기', edit→'편집'", () => {
    expect(roleActionLabel("view")).toBe("보기");
    expect(roleActionLabel("edit")).toBe("편집");
  });
  it("아이콘: view→'eye', edit→'edit'", () => {
    expect(roleActionIcon("view")).toBe("eye");
    expect(roleActionIcon("edit")).toBe("edit");
  });
});
