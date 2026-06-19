import { describe, it, expect } from "vitest";
import { EditorState } from "@codemirror/state";
import { CompletionContext } from "@codemirror/autocomplete";
import { wikilinkCompletion } from "./wikilinkComplete";

const cands = () => [
  { id: "n1", title: "배포 런북", path: "운영" },
  { id: "n2", title: "회의록", path: "팀" },
];

function ctxFor(doc: string, explicit = false) {
  const state = EditorState.create({ doc });
  return new CompletionContext(state, doc.length, explicit);
}

describe("wikilinkCompletion source (격리)", () => {
  const src = wikilinkCompletion(cands);

  it("[[ 직후 빈 쿼리 → 전체 후보", () => {
    const r: any = src(ctxFor("[["));
    expect(r).not.toBeNull();
    expect(r.options.length).toBe(2);
  });

  it("[[배 → 제목 필터", () => {
    const r: any = src(ctxFor("[[배"));
    expect(r.options.map((o: any) => o.label)).toEqual(["배포 런북"]);
  });

  it("from은 '[[' 다음 — 쿼리가 제목과 매칭되도록", () => {
    const r: any = src(ctxFor("[[배")); // "[[배" 중 "[[" 다음 = 인덱스 2
    expect(r.from).toBe(2);
  });

  it("apply는 트리거 제외 나머지(id:..|..]]) — '[['는 이미 입력됨", () => {
    const r: any = src(ctxFor("[[배"));
    expect(r.options[0].apply).toBe("id:n1|배포 런북]]");
  });

  it("문장 중간 [[ 도 매칭", () => {
    const r: any = src(ctxFor("앞 글자 [[회"));
    expect(r.options.map((o: any) => o.label)).toEqual(["회의록"]);
  });

  it("[[ 없으면 null", () => {
    expect(src(ctxFor("그냥 텍스트"))).toBeNull();
  });
});
