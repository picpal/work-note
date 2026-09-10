import { describe, it, expect } from "vitest";
import { formatBytes, countAttachmentRefs, attachmentDeleteMessage } from "./AttachmentBar";

describe("formatBytes", () => {
  it("1KB 미만은 B 단위", () => {
    expect(formatBytes(0)).toBe("0 B");
    expect(formatBytes(512)).toBe("512 B");
    expect(formatBytes(1023)).toBe("1023 B");
  });
  it("1MB 미만은 KB 단위(소수 1자리)", () => {
    expect(formatBytes(1024)).toBe("1.0 KB");
    expect(formatBytes(2048)).toBe("2.0 KB");
    expect(formatBytes(1536)).toBe("1.5 KB");
  });
  it("1MB 이상은 MB 단위(소수 1자리)", () => {
    expect(formatBytes(1024 * 1024)).toBe("1.0 MB");
    expect(formatBytes(5 * 1024 * 1024)).toBe("5.0 MB");
  });
});

// 첨부 삭제 경고 부실(QA): 본문이 쓰고 있는 이미지를 지워도 "몇 군데 쓰이는지"를 안 알려줬다.
// 노트 삭제는 "N개 문서가 참조" 경고를 띄우면서 첨부는 침묵하던 비대칭을 없앤다.
describe("countAttachmentRefs", () => {
  const url = "/api/attachments/att-1";

  it("본문에 없으면 0", () => {
    expect(countAttachmentRefs("아무 내용", url)).toBe(0);
    expect(countAttachmentRefs("", url)).toBe(0);
  });

  it("이미지 마크다운·링크·img 태그를 모두 센다", () => {
    const doc = [
      "![캡션](/api/attachments/att-1)",
      "본문",
      "[내려받기](/api/attachments/att-1)",
      '<img src="/api/attachments/att-1" width="60%">',
    ].join("\n");
    expect(countAttachmentRefs(doc, url)).toBe(3);
  });

  it("id가 더 긴 다른 첨부에 부분 매칭되지 않는다", () => {
    const doc = "![a](/api/attachments/att-12)\n![b](/api/attachments/att-1)";
    expect(countAttachmentRefs(doc, url)).toBe(1);
  });

  it("url에 정규식 특수문자가 있어도 리터럴로 센다", () => {
    expect(countAttachmentRefs("![x](/api/attachments/a.b+c)", "/api/attachments/a.b+c")).toBe(1);
    expect(countAttachmentRefs("![x](/api/attachments/aXbYc)", "/api/attachments/a.b+c")).toBe(0);
  });

  it("본문·url이 비면 0", () => {
    expect(countAttachmentRefs("![x](/api/attachments/att-1)", "")).toBe(0);
  });
});

describe("attachmentDeleteMessage", () => {
  it("본문에서 쓰이면 사용처 수를 알려준다", () => {
    const lines = attachmentDeleteMessage("도면.png", 2);
    expect(lines[0]).toContain("'도면.png'");
    expect(lines[0]).toContain("되돌릴 수 없습니다");
    expect(lines[1]).toContain("2곳");
  });

  it("본문에서 안 쓰이면 그렇다고 알려준다", () => {
    expect(attachmentDeleteMessage("사양서.pdf", 0)[1]).toContain("사용하고 있지 않습니다");
  });

  it("본문을 알 수 없으면(에디터 없음) 참조 줄을 만들지 않는다", () => {
    expect(attachmentDeleteMessage("사양서.pdf", null)).toHaveLength(1);
  });
});
