import { describe, it, expect } from "vitest";
import {
  wikilinkView,
  wikilinkNeedsRepaint,
  wikilinkClickAction,
  WIKILINK_BROKEN_TEXT,
  WIKILINK_BROKEN_HINT,
} from "./wikilinkState";

describe("wikilinkView", () => {
  it("해석되면 라벨을 보여주고 정상 클래스", () => {
    const v = wikilinkView("배포 런북", "런북");
    expect(v.broken).toBe(false);
    expect(v.className).toBe("cm-wikilink");
    expect(v.text).toBe("런북");
    expect(v.hint).toBe("배포 런북");
  });

  it("라벨이 없으면 대상 제목을 보여준다", () => {
    expect(wikilinkView("배포 런북", "").text).toBe("배포 런북");
  });

  it("해석 실패(null)면 끊긴 링크 — 라벨이 있어도 안내 문구로 대체", () => {
    const v = wikilinkView(null, "런북");
    expect(v.broken).toBe(true);
    expect(v.className).toBe("cm-wikilink broken");
    expect(v.text).toBe(WIKILINK_BROKEN_TEXT);
    expect(v.hint).toBe(WIKILINK_BROKEN_HINT);
  });
});

describe("wikilinkNeedsRepaint", () => {
  it("같은 상태면 다시 칠하지 않는다", () => {
    const a = wikilinkView("배포 런북", "런북");
    const b = wikilinkView("배포 런북", "런북");
    expect(wikilinkNeedsRepaint(a, b)).toBe(false);
  });

  it("정상 → 끊김(대상 삭제)이면 다시 칠한다", () => {
    expect(wikilinkNeedsRepaint(wikilinkView("배포 런북", ""), wikilinkView(null, ""))).toBe(true);
  });

  it("끊김 → 정상(휴지통 복구)이면 다시 칠한다", () => {
    expect(wikilinkNeedsRepaint(wikilinkView(null, ""), wikilinkView("배포 런북", ""))).toBe(true);
  });

  it("대상 제목만 바뀌어도(라벨 없는 링크) 다시 칠한다", () => {
    expect(wikilinkNeedsRepaint(wikilinkView("이전 제목", ""), wikilinkView("새 제목", ""))).toBe(true);
  });

  it("라벨 링크는 표시 텍스트가 같아도 대상 제목(hint)이 바뀌면 다시 칠한다", () => {
    expect(wikilinkNeedsRepaint(wikilinkView("이전 제목", "런북"), wikilinkView("새 제목", "런북"))).toBe(true);
  });
});

describe("wikilinkClickAction", () => {
  it("정상 링크는 이동", () => {
    expect(wikilinkClickAction(wikilinkView("배포 런북", ""))).toBe("navigate");
  });

  it("끊긴 링크는 이동하지 않고 안내만 — 보던 노트가 닫히면 안 된다(D-7)", () => {
    expect(wikilinkClickAction(wikilinkView(null, ""))).toBe("notice");
  });
});
