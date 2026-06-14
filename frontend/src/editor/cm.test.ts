// @vitest-environment jsdom
import { describe, it, expect } from "vitest";
import * as cm from "./cm";

describe("cm.insertAtCursor", () => {
  it("커서 위치에 텍스트를 삽입한다", () => {
    const host = document.createElement("div");
    const view = cm.create(host, { doc: "ab" });
    view.dispatch({ selection: { anchor: 1 } });
    cm.insertAtCursor(view, "X");
    expect(view.state.doc.toString()).toBe("aXb");
    view.destroy();
  });
});

// Regression: 에디터 라이브프리뷰가 이미지를 렌더하지 않던 문제(/qa e2e 발견)
// Found by /qa on 2026-06-14 — cm.ts에 인라인 이미지 위젯(ImageWidget) 추가
describe("cm 인라인 이미지 미리보기", () => {
  it("![](/api/attachments/..) 마크다운을 실제 <img>로 렌더한다", () => {
    const host = document.createElement("div");
    document.body.appendChild(host);
    const view = cm.create(host, { doc: "![캡션](/api/attachments/att-1)" });
    const img = host.querySelector(".cm-md-image img") as HTMLImageElement | null;
    expect(img).not.toBeNull();
    expect(img!.getAttribute("src")).toBe("/api/attachments/att-1");
    view.destroy();
    host.remove();
  });

  it("외부 http(s) src 마크다운 이미지는 차단 위젯으로 렌더한다(실제 img 없음)", () => {
    const host = document.createElement("div");
    document.body.appendChild(host);
    const view = cm.create(host, { doc: "![x](https://evil.example/p.gif)" });
    expect(host.querySelector(".cm-md-image img")).toBeNull();
    expect(host.querySelector(".cm-md-image.blocked")).not.toBeNull();
    view.destroy();
    host.remove();
  });

  it("<img src=\"/api/attachments/..\" width> 태그를 width 보존해 렌더한다", () => {
    const host = document.createElement("div");
    document.body.appendChild(host);
    const view = cm.create(host, { doc: '<img src="/api/attachments/att-2" width="60%">' });
    const img = host.querySelector(".cm-md-image img") as HTMLImageElement | null;
    expect(img).not.toBeNull();
    expect(img!.getAttribute("width")).toBe("60%");
    view.destroy();
    host.remove();
  });
});
