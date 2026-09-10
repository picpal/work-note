import { WidgetType, Decoration, EditorView, ViewPlugin } from "@codemirror/view";
import type { ViewUpdate } from "@codemirror/view";
import { Facet } from "@codemirror/state";
import type { EditorState, Extension, Range } from "@codemirror/state";
import { WIKILINK_RE } from "../lib/wikilink";
import { wikilinkView, wikilinkNeedsRepaint, wikilinkClickAction, type WikilinkView } from "./wikilinkState";

export interface WikiConfig {
  resolve: (id: string) => string | null; // 대상 현재 title, 못 보거나 없으면 null
  navigate: (id: string) => void;
}

export const wikiConfigFacet = Facet.define<WikiConfig, WikiConfig | null>({
  combine: (vals) => (vals.length ? vals[0] : null),
});

/** 끊긴 링크 클릭 안내를 띄워두는 시간(ms). CSS는 cm.ts baseTheme의 .cm-wikilink.broken.notice::after. */
const NOTICE_MS = 2000;
/** 화면에 그려진 토큰만 다시 해석하는 주기(ms).
   대상 삭제는 에디터 밖(사이드바)에서 일어나 트랜잭션을 만들지 않으므로 CM은 알 방법이 없다.
   비용은 "뷰포트 안 링크 수"에 비례하고 문서 길이와 무관 — 재파싱은 하지 않는다. */
export const WIKILINK_REFRESH_MS = 400;

const noticeTimers = new WeakMap<HTMLElement, ReturnType<typeof setTimeout>>();

/** 토큰 DOM에 현재 표시 상태를 반영한다(dataset에 상태를 남겨 다음 갱신 때 비교에 쓴다). */
function paintWikilink(el: HTMLElement, v: WikilinkView): void {
  el.className = v.className;
  el.textContent = v.text;
  el.title = v.hint;
  el.dataset.broken = v.broken ? "1" : "0";
}

/** 토큰 DOM이 현재 무엇을 보여주고 있는지 되읽는다 — paintWikilink의 역함수. */
function readWikilink(el: HTMLElement): WikilinkView {
  return {
    broken: el.dataset.broken === "1",
    className: el.className.replace(/\s*\bnotice\b/, ""), // 일시적 안내 클래스는 상태가 아님
    text: el.textContent || "",
    hint: el.title,
  };
}

/** 끊긴 링크를 클릭했을 때의 안내 — 이동 대신 토큰 아래에 잠깐 문구를 띄운다. */
function showBrokenNotice(el: HTMLElement): void {
  const prev = noticeTimers.get(el);
  if (prev) clearTimeout(prev);
  el.classList.add("notice");
  noticeTimers.set(el, setTimeout(() => el.classList.remove("notice"), NOTICE_MS));
}

class WikilinkWidget extends WidgetType {
  constructor(readonly id: string, readonly label: string, readonly cfg: WikiConfig) { super(); }
  // id·label만 비교한다: 여기서 resolve까지 하면 데코레이션 재계산(=키 입력마다 문서 전체 스캔)에
  // 링크 수만큼 트리 탐색이 붙는다. 해석 결과 변화는 refreshWikilinks가 화면 토큰만 골라 반영한다.
  eq(o: WikilinkWidget) { return o.id === this.id && o.label === this.label; }
  toDOM() {
    const el = document.createElement("span");
    el.dataset.noteId = this.id;
    el.dataset.label = this.label;
    paintWikilink(el, wikilinkView(this.cfg.resolve(this.id), this.label));
    el.addEventListener("mousedown", (e) => {
      e.preventDefault();
      // 클릭 시점에 다시 해석한다 — 그 사이 대상이 삭제됐을 수 있고,
      // 없는 노트로 navigate하면 보고 있던 노트까지 닫힌다(D-7).
      const now = wikilinkView(this.cfg.resolve(this.id), this.label);
      if (wikilinkNeedsRepaint(readWikilink(el), now)) paintWikilink(el, now);
      if (wikilinkClickAction(now) === "navigate") this.cfg.navigate(this.id);
      else showBrokenNotice(el);
    });
    return el;
  }
  ignoreEvent() { return false; }
}

/** 화면에 그려진 위키링크 토큰만 다시 해석해 표시를 맞춘다. 바뀐 토큰 수를 돌려준다.
   문서 재파싱·데코레이션 재계산 없음: CodeMirror는 뷰포트 안 줄만 DOM으로 만들므로
   비용은 화면에 보이는 링크 수(보통 한 자리)에 비례한다. */
export function refreshWikilinks(view: EditorView): number {
  const cfg = view.state.facet(wikiConfigFacet);
  if (!cfg) return 0;
  const els = view.dom.querySelectorAll<HTMLElement>("span.cm-wikilink[data-note-id]");
  if (els.length === 0) return 0;
  const memo = new Map<string, string | null>(); // 같은 노트를 여러 번 링크해도 해석은 1회
  let changed = 0;
  els.forEach((el) => {
    const id = el.dataset.noteId as string;
    if (!memo.has(id)) memo.set(id, cfg.resolve(id));
    const next = wikilinkView(memo.get(id) ?? null, el.dataset.label || "");
    if (wikilinkNeedsRepaint(readWikilink(el), next)) { paintWikilink(el, next); changed++; }
  });
  return changed;
}

// 노트 삭제/복구/제목 변경은 에디터 바깥(사이드바·관리자)에서 일어나 CM 트랜잭션을 만들지 않는다.
// 그래서 (1) 뷰 업데이트마다, (2) 그 사이엔 짧은 주기로 화면 토큰만 다시 해석한다.
// 탭이 숨겨져 있으면 쉰다.
const wikilinkTimer = ViewPlugin.fromClass(
  class {
    timer: ReturnType<typeof setInterval>;
    constructor(view: EditorView) {
      this.timer = setInterval(() => {
        if (typeof document !== "undefined" && document.hidden) return;
        refreshWikilinks(view);
      }, WIKILINK_REFRESH_MS);
    }
    destroy() { clearInterval(this.timer); }
  },
);

// updateListener는 DOM 반영 "후"에 불린다(ViewPlugin.update는 이전) — 새로 그려진 토큰까지 확실히 잡힌다.
const wikilinkOnUpdate = EditorView.updateListener.of((u: ViewUpdate) => {
  if (u.docChanged || u.viewportChanged || u.focusChanged) refreshWikilinks(u.view);
});

/** 위키링크 표시 갱신 확장 — wikiConfigFacet과 함께 붙인다. */
export const wikilinkRefresh: Extension = [wikilinkTimer, wikilinkOnUpdate];

// 활성 줄이 아닌 [[id:..]]를 토큰으로 치환하는 데코레이션 목록. 설정 없으면 빈 배열.
export function wikilinkDecorations(state: EditorState, isLineActive: (pos: number) => boolean): Range<Decoration>[] {
  const cfg = state.facet(wikiConfigFacet);
  if (!cfg) return [];
  const out: Range<Decoration>[] = [];
  const text = state.doc.toString();
  WIKILINK_RE.lastIndex = 0;
  let m: RegExpExecArray | null;
  while ((m = WIKILINK_RE.exec(text))) {
    const from = m.index, to = from + m[0].length;
    if (isLineActive(from)) continue; // 커서가 그 줄이면 원문 노출(편집)
    out.push(Decoration.replace({ widget: new WikilinkWidget(m[1], m[2] || "", cfg) }).range(from, to));
  }
  return out;
}
