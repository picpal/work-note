/* searchBus — "이 질의로 검색창을 열어달라" 요청을 컴포넌트 사이에 잇는 최소 버스.
   Editor(태그 칩 클릭) → Sidebar(수신 후 onOpenSearch 호출) → SearchModal(마운트 시 시드 소비).

   왜 props가 아니라 모듈 버스인가: 검색창의 열림 상태(searchOpen)는 App.tsx가 쥐고 있고
   이번 작업 범위에서 App.tsx를 수정할 수 없다. App이 Editor에 onTagClick, SearchModal에
   initialQuery를 넘겨주게 되면 이 모듈은 그대로 제거할 수 있다(리포트의 배선 항목 참조).

   시드는 "1회 소비" — 검색창이 열리며 읽어가면 사라진다. */

const EVENT = "wn:search-request";

let pending: string | null = null;

/** 검색 요청 — 질의를 시드로 남기고 열림 요청 이벤트를 쏜다. */
export function requestSearch(query: string): void {
  pending = query;
  if (typeof window !== "undefined") window.dispatchEvent(new Event(EVENT));
}

/** 시드를 1회 소비. 없으면 빈 문자열(= 평소처럼 빈 검색창). */
export function consumeSearchSeed(): string {
  const q = pending;
  pending = null;
  return q ?? "";
}

/** 열림 요청 구독. 해제 함수를 돌려준다(useEffect cleanup용). */
export function onSearchRequest(cb: () => void): () => void {
  if (typeof window === "undefined") return () => {};
  window.addEventListener(EVENT, cb);
  return () => window.removeEventListener(EVENT, cb);
}
