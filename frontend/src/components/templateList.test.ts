import { describe, it, expect } from "vitest";
import { groupTemplates, canEdit } from "./templateList";
import type { ApiTemplate } from "../api/templates";

const t = (id: string, name: string, system: boolean): ApiTemplate => ({ id, name, body: "## " + name, system });

describe("groupTemplates", () => {
  it("시스템과 개인을 분리한다", () => {
    const g = groupTemplates([t("1", "회의록", true), t("2", "내 양식", false)]);
    expect(g.system.map((x) => x.id)).toEqual(["1"]);
    expect(g.mine.map((x) => x.id)).toEqual(["2"]);
  });

  it("각 그룹을 이름 오름차순으로 정렬한다", () => {
    const g = groupTemplates([
      t("1", "하양식", true), t("2", "가양식", true),
      t("3", "다양식", false), t("4", "나양식", false),
    ]);
    expect(g.system.map((x) => x.name)).toEqual(["가양식", "하양식"]);
    expect(g.mine.map((x) => x.name)).toEqual(["나양식", "다양식"]);
  });

  it("입력 배열을 변형하지 않는다", () => {
    const input = [t("1", "하", true), t("2", "가", true)];
    groupTemplates(input);
    expect(input.map((x) => x.id)).toEqual(["1", "2"]);
  });

  it("빈 목록도 빈 그룹을 돌려준다", () => {
    expect(groupTemplates([])).toEqual({ system: [], mine: [] });
  });
});

describe("canEdit", () => {
  it("시스템 템플릿은 사용자 모달에서 수정 불가", () => {
    expect(canEdit(t("1", "회의록", true))).toBe(false);
  });
  it("내 템플릿은 수정 가능", () => {
    expect(canEdit(t("2", "내 양식", false))).toBe(true);
  });
});
