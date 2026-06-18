import { WidgetType, Decoration } from "@codemirror/view";
import { Facet } from "@codemirror/state";
import type { EditorState, Range } from "@codemirror/state";
import { WIKILINK_RE } from "../lib/wikilink";

export interface WikiConfig {
  resolve: (id: string) => string | null; // 대상 현재 title, 못 보거나 없으면 null
  navigate: (id: string) => void;
}

export const wikiConfigFacet = Facet.define<WikiConfig, WikiConfig | null>({
  combine: (vals) => (vals.length ? vals[0] : null),
});

class WikilinkWidget extends WidgetType {
  constructor(readonly id: string, readonly label: string, readonly cfg: WikiConfig) { super(); }
  eq(o: WikilinkWidget) { return o.id === this.id && o.label === this.label; }
  toDOM() {
    const title = this.cfg.resolve(this.id);
    const el = document.createElement("span");
    if (title == null) {
      el.className = "cm-wikilink broken";
      el.textContent = "🔒 연결할 수 없음";
      return el;
    }
    el.className = "cm-wikilink";
    el.textContent = this.label || title;
    el.addEventListener("mousedown", (e) => { e.preventDefault(); this.cfg.navigate(this.id); });
    return el;
  }
  ignoreEvent() { return false; }
}

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
