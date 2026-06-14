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
