// @vitest-environment jsdom
// 첨부 삭제 확인이 "본문 N곳에서 쓰는 중"을 말하려면 현재 노트 본문이 필요하다.
// 그 통로가 cm.create ↔ activeDoc 등록이라, 배선이 끊기면 경고가 조용히 반쪽이 된다.
import { describe, it, expect } from "vitest";
import * as cm from "./cm";
import { activeDocText } from "./activeDoc";

function mount(doc: string) {
  const host = document.createElement("div");
  document.body.appendChild(host);
  const view = cm.create(host, { doc });
  return { view, close: () => { view.destroy(); host.remove(); } };
}

describe("activeDocText", () => {
  it("열린 에디터가 없으면 null (공유 열람 화면 등)", () => {
    expect(activeDocText()).toBeNull();
  });

  it("에디터가 떠 있는 동안 현재 본문을 돌려주고 편집을 반영한다", () => {
    const a = mount("![x](/api/attachments/att-1)");
    expect(activeDocText()).toBe("![x](/api/attachments/att-1)");
    a.view.dispatch({ changes: { from: 0, insert: "머리말\n" } });
    expect(activeDocText()).toBe("머리말\n![x](/api/attachments/att-1)");
    a.close();
  });

  it("에디터를 닫으면 다시 null", () => {
    const a = mount("본문");
    a.close();
    expect(activeDocText()).toBeNull();
  });

  it("노트를 바꿔 새 에디터가 뜬 뒤 옛 에디터가 닫혀도 새 본문을 가리킨다", () => {
    const a = mount("옛 노트");
    const b = mount("새 노트");
    a.close(); // 언마운트 순서가 뒤집혀도 최신 등록이 살아 있어야 한다
    expect(activeDocText()).toBe("새 노트");
    b.close();
    expect(activeDocText()).toBeNull();
  });
});
