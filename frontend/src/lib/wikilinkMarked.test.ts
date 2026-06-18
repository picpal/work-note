import { describe, it, expect } from "vitest";
import { renderWikiInline } from "./markdown";

describe("renderWikiInline (marked extension)", () => {
  it("라벨 있는 링크 → data-note-id a, href 없음", () => {
    const html = renderWikiInline("[[id:abc|배포 런북]]");
    expect(html).toContain('class="wikilink"');
    expect(html).toContain('data-note-id="abc"');
    expect(html).toContain("배포 런북");
    expect(html).not.toContain("href");
  });

  it("라벨 없는 링크는 기본 표기", () => {
    expect(renderWikiInline("[[id:abc]]")).toContain("🔗");
  });

  it("라벨의 HTML은 escape된다(XSS 차단)", () => {
    const html = renderWikiInline("[[id:x|<img src=x onerror=alert(1)>]]");
    expect(html).not.toContain("<img");
    expect(html).toContain("&lt;img");
  });

  it("일반 마크다운 링크는 건드리지 않는다", () => {
    expect(renderWikiInline("[보통](https://e.com)")).toContain("href");
  });
});
