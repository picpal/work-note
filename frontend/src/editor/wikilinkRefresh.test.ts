// @vitest-environment jsdom
// D-7 회귀: 링크 대상 노트를 삭제해도 토큰이 파란 정상 링크로 남고(새로고침해야 broken),
// 그 링크를 누르면 없는 id로 navigate돼 보고 있던 노트까지 닫혔다.
// 대상 삭제는 사이드바에서 일어나 CM 트랜잭션을 만들지 않으므로, 화면 토큰만 다시 해석하는
// refreshWikilinks가 갱신 경로다. CM 라이브 통합 검증.
import { describe, it, expect } from "vitest";
import * as cm from "./cm";
import { refreshWikilinks, WIKILINK_REFRESH_MS } from "./wikilinkWidget";
import { WIKILINK_BROKEN_TEXT } from "./wikilinkState";

const DOC = "본문 첫 줄\n\n참고: [[id:n2|B노트]] 를 보라\n";

function mount(opts: { titles: Map<string, string>; nav: string[]; resolveCalls?: { n: number }; doc?: string }) {
  const host = document.createElement("div");
  document.body.appendChild(host);
  const view = cm.create(host, {
    doc: opts.doc ?? DOC,
    wiki: {
      resolve: (id) => { if (opts.resolveCalls) opts.resolveCalls.n++; return opts.titles.get(id) ?? null; },
      navigate: (id) => { opts.nav.push(id); },
    },
  });
  const token = () => host.querySelector("span.cm-wikilink") as HTMLElement | null;
  const click = () => token()!.dispatchEvent(new MouseEvent("mousedown", { bubbles: true, cancelable: true }));
  const done = () => { view.destroy(); host.remove(); };
  return { host, view, token, click, done };
}

describe("위키링크 토큰 무효화(D-7)", () => {
  it("대상이 살아 있으면 라벨을 단 정상 링크로 그린다", () => {
    const t = mount({ titles: new Map([["n2", "B노트"]]), nav: [] });
    expect(t.token()!.className).toBe("cm-wikilink");
    expect(t.token()!.textContent).toBe("B노트");
    t.done();
  });

  it("대상이 삭제되면 새로고침 없이 '연결할 수 없음'으로 바뀐다", () => {
    const titles = new Map([["n2", "B노트"]]);
    const t = mount({ titles, nav: [] });
    expect(t.token()!.className).toBe("cm-wikilink");

    titles.delete("n2"); // 사이드바에서 노트 B 삭제 — 에디터에는 아무 트랜잭션도 안 온다
    expect(refreshWikilinks(t.view)).toBe(1);

    expect(t.token()!.className).toBe("cm-wikilink broken");
    expect(t.token()!.textContent).toBe(WIKILINK_BROKEN_TEXT);
    t.done();
  });

  it("휴지통에서 복구되면 다시 정상 링크로 돌아온다", () => {
    const titles = new Map<string, string>();
    const t = mount({ titles, nav: [] });
    expect(t.token()!.className).toBe("cm-wikilink broken");

    titles.set("n2", "B노트");
    expect(refreshWikilinks(t.view)).toBe(1);
    expect(t.token()!.className).toBe("cm-wikilink");
    expect(t.token()!.textContent).toBe("B노트");
    t.done();
  });

  it("바뀐 게 없으면 DOM을 건드리지 않는다", () => {
    const t = mount({ titles: new Map([["n2", "B노트"]]), nav: [] });
    expect(refreshWikilinks(t.view)).toBe(0);
    expect(refreshWikilinks(t.view)).toBe(0);
    t.done();
  });

  it("같은 노트를 여러 번 링크해도 해석은 노트당 1회 (갱신 비용은 화면 토큰 수에 비례)", () => {
    const calls = { n: 0 };
    const t = mount({
      titles: new Map([["n2", "B노트"]]),
      nav: [],
      resolveCalls: calls,
      doc: "가\n\n[[id:n2]]\n\n[[id:n2]]\n\n[[id:n2]]\n",
    });
    calls.n = 0; // 최초 렌더(toDOM) 몫은 제외
    refreshWikilinks(t.view);
    expect(calls.n).toBe(1);
    t.done();
  });

  it("갱신은 문서를 고치지 않는다 — 마크다운 원문 왕복 무결성", () => {
    const titles = new Map([["n2", "B노트"]]);
    const t = mount({ titles, nav: [] });
    titles.delete("n2");
    refreshWikilinks(t.view);
    expect(t.view.state.doc.toString()).toBe(DOC);
    t.done();
  });
});

describe("끊긴 위키링크 클릭(D-7 후반부)", () => {
  it("정상 링크는 이동한다", () => {
    const nav: string[] = [];
    const t = mount({ titles: new Map([["n2", "B노트"]]), nav });
    t.click();
    expect(nav).toEqual(["n2"]);
    t.done();
  });

  it("끊긴 링크는 navigate를 부르지 않는다 — 보던 노트가 닫히면 안 된다", () => {
    const nav: string[] = [];
    const titles = new Map([["n2", "B노트"]]);
    const t = mount({ titles, nav });
    titles.delete("n2");
    refreshWikilinks(t.view);
    t.click();
    expect(nav).toEqual([]);
    expect(t.token()!.classList.contains("notice")).toBe(true); // 아무 일도 없는 대신 안내
    t.done();
  });

  it("갱신 패스가 아직 안 돌았어도, 클릭 시점에 다시 해석해 이동을 막고 그 자리에서 표시를 고친다", () => {
    const nav: string[] = [];
    const titles = new Map([["n2", "B노트"]]);
    const t = mount({ titles, nav });
    titles.delete("n2"); // 삭제 직후 곧바로 클릭
    t.click();
    expect(nav).toEqual([]);
    expect(t.token()!.className).toContain("broken");
    t.done();
  });
});

// 갱신 함수가 있는 것만으로는 부족하다 — 에디터에 실제로 붙어 돌아야 "새로고침 없이" 바뀐다.
describe("주기 갱신 배선", () => {
  const wait = (ms: number) => new Promise((r) => setTimeout(r, ms));

  it("트랜잭션 없이(사이드바 삭제만으로) 토큰이 스스로 끊긴 상태가 된다", async () => {
    const titles = new Map([["n2", "B노트"]]);
    const t = mount({ titles, nav: [] });
    expect(t.token()!.className).toBe("cm-wikilink");

    titles.delete("n2");
    await wait(WIKILINK_REFRESH_MS + 150); // 에디터를 건드리지 않는다

    expect(t.token()!.className).toBe("cm-wikilink broken");
    t.done();
  });

  it("에디터를 닫으면 주기 갱신도 멈춘다(타이머 누수 없음)", async () => {
    const calls = { n: 0 };
    const t = mount({ titles: new Map([["n2", "B노트"]]), nav: [], resolveCalls: calls });
    t.done();
    calls.n = 0;
    await wait(WIKILINK_REFRESH_MS + 150);
    expect(calls.n).toBe(0);
  });
});
