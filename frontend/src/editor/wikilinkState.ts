/* 위키링크 토큰의 표시 상태 판정 — 순수 함수만. DOM·CodeMirror 의존 없음.
   D-7 회귀: 대상 노트가 삭제돼도 토큰이 파란 정상 링크로 남고, 클릭하면 보던 노트까지 닫혔다.
   판정을 여기로 모아서 (1) 다시 칠해야 하는지 (2) 클릭을 네비게이션으로 볼지를 테스트 가능하게 한다. */

export const WIKILINK_LIVE_CLASS = "cm-wikilink";
export const WIKILINK_BROKEN_CLASS = "cm-wikilink broken";
export const WIKILINK_BROKEN_TEXT = "🔒 연결할 수 없음";
export const WIKILINK_BROKEN_HINT = "삭제되었거나 볼 수 없는 노트입니다";

/** 화면에 그려질 링크 토큰의 상태. resolve 결과(제목 또는 null)와 라벨에서만 계산된다. */
export interface WikilinkView {
  broken: boolean;
  className: string;
  text: string;
  /** title 속성(hover 안내). 정상 링크면 대상 제목, 끊겼으면 사유. */
  hint: string;
}

/** resolve(id) 결과와 라벨 → 표시 상태. resolved==null(삭제·권한 회수)이면 끊긴 링크. */
export function wikilinkView(resolved: string | null, label: string): WikilinkView {
  if (resolved == null) {
    return { broken: true, className: WIKILINK_BROKEN_CLASS, text: WIKILINK_BROKEN_TEXT, hint: WIKILINK_BROKEN_HINT };
  }
  return { broken: false, className: WIKILINK_LIVE_CLASS, text: label || resolved, hint: resolved };
}

/** 이미 그려진 상태 prev와 새로 계산한 next가 달라 DOM을 다시 칠해야 하는가.
   같으면 건드리지 않는다 — 갱신 패스가 매번 DOM을 쓰지 않도록 하는 게 목적. */
export function wikilinkNeedsRepaint(prev: WikilinkView, next: WikilinkView): boolean {
  return prev.broken !== next.broken || prev.className !== next.className || prev.text !== next.text || prev.hint !== next.hint;
}

/** 클릭 처리 결정. 끊긴 링크는 절대 navigate하지 않는다 —
   존재하지 않는 id로 navigate하면 활성 노트가 사라져 "열린 노트가 없습니다"가 되던 D-7의 후반부. */
export function wikilinkClickAction(v: WikilinkView): "navigate" | "notice" {
  return v.broken ? "notice" : "navigate";
}
