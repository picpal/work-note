import type { CompletionContext, CompletionSource, Completion } from "@codemirror/autocomplete";
import { makeLink } from "../lib/wikilink";

export interface WikiCandidate {
  id: string;
  title: string;
  path: string; // "A / B" 폴더 경로 — 동명 노트 구분용
}

// [[ 트리거 자동완성. 이미 [[id: 가 들어간(완성된) 링크 편집 중이면 비활성.
export function wikilinkCompletion(getCandidates: () => WikiCandidate[]): CompletionSource {
  return (ctx: CompletionContext) => {
    const before = ctx.matchBefore(/\[\[([^\]]*)$/);
    if (!before) return null;
    const typed = before.text.slice(2); // "[[" 제거
    if (typed.startsWith("id:")) return null; // 완성된 링크 편집 중
    const q = typed.toLowerCase();
    const options: Completion[] = getCandidates()
      .filter((c) => !q || c.title.toLowerCase().includes(q) || c.path.toLowerCase().includes(q))
      .slice(0, 50)
      .map((c) => {
        const title = c.title || "제목 없음";
        return { label: title, detail: c.path, type: "wikilink", apply: makeLink(c.id, title) };
      });
    if (!options.length) return null;
    return { from: before.from, options, validFor: /^\[\[[^\]]*$/ };
  };
}
