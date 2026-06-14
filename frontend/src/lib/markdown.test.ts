// @vitest-environment jsdom
import { describe, it, expect } from "vitest";
import { renderMarkdown } from "./markdown";

describe("renderMarkdown 이미지 src 제한", () => {
  it("내부 첨부 src는 보존한다", () => {
    const html = renderMarkdown("![cap](/api/attachments/att-1)");
    expect(html).toContain('src="/api/attachments/att-1"');
  });
  it("상대경로 src는 보존한다", () => {
    expect(renderMarkdown("![x](images/a.png)")).toContain('src="images/a.png"');
  });
  it("외부 http(s) src는 제거한다", () => {
    const html = renderMarkdown('<img src="https://evil.example/p.gif">');
    expect(html).not.toContain("evil.example");
  });
  it("data: src는 제거한다", () => {
    const html = renderMarkdown('<img src="data:image/png;base64,AAAA">');
    expect(html).not.toContain("data:image");
  });
  it("protocol-relative(//) src는 제거한다", () => {
    const html = renderMarkdown('<img src="//evil.example/p.gif">');
    expect(html).not.toContain("evil.example");
  });
  it("<img>의 width 속성은 보존한다", () => {
    const html = renderMarkdown('<img src="/api/attachments/att-1" width="100%">');
    expect(html).toContain('width="100%"');
  });
});

describe("renderMarkdown 첨부 칩", () => {
  it("/api/attachments 링크에 attach-chip 클래스를 부여한다", () => {
    const html = renderMarkdown("[보고서.pdf](/api/attachments/att-2)");
    expect(html).toContain("attach-chip");
  });
});
