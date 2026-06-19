import type { CompletionContext, CompletionSource, Completion } from "@codemirror/autocomplete";
import { makeLink } from "../lib/wikilink";

export interface WikiCandidate {
  id: string;
  title: string;
  path: string; // "A / B" 폴더 경로 — 동명 노트 구분용
}

// [[ 트리거 자동완성. 이미 [[id: 가 들어간(완성된) 링크 편집 중이면 비활성.
// from은 "[[" *다음*을 가리킨다 — 그래야 CM 기본 필터가 [from,커서] 텍스트(=입력한 검색어)를
// 옵션 제목과 매칭한다. from을 "[["에 두면 쿼리가 "[[…"가 되어 제목과 안 맞아 전부 걸러진다.
// 따라서 apply에는 트리거 "[["를 뺀 나머지(id:..|..]])만 넣는다("[["는 이미 입력돼 있음).
export function wikilinkCompletion(getCandidates: () => WikiCandidate[]): CompletionSource {
  return (ctx: CompletionContext) => {
    const before = ctx.matchBefore(/\[\[([^\]]*)$/);
    if (!before) return null;
    const typed = before.text.slice(2); // "[[" 이후 입력(검색어)
    if (typed.startsWith("id:")) return null; // 완성된 링크 편집 중
    const q = typed.toLowerCase();
    const options: Completion[] = getCandidates()
      .filter((c) => !q || c.title.toLowerCase().includes(q) || c.path.toLowerCase().includes(q))
      .slice(0, 50)
      .map((c) => {
        const title = c.title || "제목 없음";
        return { label: title, detail: c.path, type: "wikilink", apply: makeLink(c.id, title).slice(2) };
      });
    if (!options.length) return null;
    return { from: before.from + 2, options, validFor: /^[^\]]*$/ };
  };
}
