// @vitest-environment jsdom
// 회귀: [[ 자동완성이 "열렸다 즉시 닫히던" 버그(from을 "[["에 둬서 쿼리 "[["가 제목과
// 안 맞아 전부 필터링) — from을 "[[" 다음으로 옮겨 해결. CM 라이브 통합 검증.
import { describe, it, expect } from "vitest";
import * as cm from "./cm";
import { completionStatus, currentCompletions } from "@codemirror/autocomplete";

const wait = (ms: number) => new Promise((r) => setTimeout(r, ms));
function typeCh(view: any, ch: string) {
  const pos = view.state.selection.main.head;
  view.dispatch({ changes: { from: pos, insert: ch }, selection: { anchor: pos + ch.length }, userEvent: "input.type" });
}

describe("[[ 자동완성 통합(회귀)", () => {
  it("[[ 입력 시 완성이 열리고, 더 타이핑하면 제목으로 필터된다", async () => {
    const host = document.createElement("div");
    document.body.appendChild(host);
    const view = cm.create(host, {
      doc: "",
      wikiCandidates: () => [
        { id: "n1", title: "배포 런북", path: "운영" },
        { id: "n2", title: "회의록", path: "팀" },
      ],
    });
    view.focus();
    typeCh(view, "[");
    typeCh(view, "[");
    await wait(200);
    expect(completionStatus(view.state)).not.toBeNull(); // 열림
    expect(currentCompletions(view.state).length).toBe(2); // 전체 후보

    typeCh(view, "배");
    await wait(200);
    expect(currentCompletions(view.state).map((o) => o.label)).toEqual(["배포 런북"]); // 필터

    view.destroy();
    host.remove();
  });
});
