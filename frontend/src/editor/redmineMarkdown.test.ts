import { describe, it, expect } from "vitest";
import { metaTableMd, bodyMd, commentMd } from "./redmineMarkdown";

const detail = {
  id: 1234, subject: "결제 타임아웃", description: "본문\n둘째줄",
  statusName: "In Progress", assignedToName: "김OO", projectName: "결제",
  priorityName: "높음", dueDate: "2026-07-01", updatedOn: "x", comments: [],
};

describe("metaTableMd", () => {
  it("GFM 표 + 출처 인용을 만든다", () => {
    const md = metaTableMd(detail);
    expect(md).toContain("| 상태 | 담당 | 우선순위 | 마감 |");
    expect(md).toContain("| In Progress | 김OO | 높음 | 2026-07-01 |");
    expect(md).toContain("> 🔗 redmine #1234");
  });
  it("담당/마감 없으면 - 로 채운다", () => {
    const md = metaTableMd({ ...detail, assignedToName: null, dueDate: null });
    expect(md).toContain("| In Progress | - | 높음 | - |");
  });
});

describe("bodyMd", () => {
  it("본문을 트림 + 선행/후행 개행으로 분리한다", () => {
    expect(bodyMd(detail)).toBe("\n본문\n둘째줄\n");
  });
});

describe("블록 분리(선행 개행)", () => {
  it("모든 블록은 선행 개행으로 시작 — 앞 내용과 붙지 않음", () => {
    expect(metaTableMd(detail).startsWith("\n")).toBe(true);
    expect(bodyMd(detail).startsWith("\n")).toBe(true);
    expect(commentMd({ userName: "홍", createdOn: "x", notes: "n" }).startsWith("\n")).toBe(true);
  });
  it("본문 뒤 메타표 연속 삽입 시 표 앞에 빈 줄이 생긴다", () => {
    const combined = bodyMd(detail) + metaTableMd(detail);
    expect(combined).toContain("둘째줄\n\n| 상태 |");
  });
});

describe("commentMd", () => {
  it("작성자/날짜 헤더 + 인용 본문", () => {
    const md = commentMd({ userName: "홍", createdOn: "2026-06-20T10:00:00Z", notes: "리뷰\n해주세요" });
    expect(md).toContain("> **홍**");
    expect(md).toContain("2026-06-20");
    expect(md).toContain("> 리뷰");
    expect(md).toContain("> 해주세요");
  });
});
