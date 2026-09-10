import { describe, it, expect } from "vitest";
import { parseSearchQuery, matchNote, rankMatches, tagQuery, type SearchTarget } from "./searchMatch";

const target = (p: Partial<SearchTarget> = {}): SearchTarget => ({ title: "", text: "", tags: [], ...p });

describe("parseSearchQuery", () => {
  it("일반 질의는 소문자 정규화 + tagOnly=false", () => {
    expect(parseSearchQuery("  QA2Tag ")).toEqual({ term: "qa2tag", tagOnly: false });
  });
  it("# 접두는 태그 전용 질의", () => {
    expect(parseSearchQuery("#qa2tag")).toEqual({ term: "qa2tag", tagOnly: true });
  });
  it("# 뒤 공백도 허용", () => {
    expect(parseSearchQuery("# 회의록")).toEqual({ term: "회의록", tagOnly: true });
  });
  it("빈 질의는 term 빈 문자열", () => {
    expect(parseSearchQuery("   ")).toEqual({ term: "", tagOnly: false });
  });
  it("'#'만 입력한 상태는 질의 없음으로 취급(tagOnly도 해제)", () => {
    expect(parseSearchQuery("#")).toEqual({ term: "", tagOnly: false });
  });
  it("tagQuery는 칩 클릭용 '#태그' 질의를 만든다", () => {
    expect(parseSearchQuery(tagQuery("qa2tag"))).toEqual({ term: "qa2tag", tagOnly: true });
  });
});

describe("matchNote", () => {
  const q = (s: string) => parseSearchQuery(s);

  it("태그만 일치해도 매칭된다 (D-5 핵심)", () => {
    const r = matchNote(target({ title: "회의", text: "본문", tags: ["qa2tag"] }), q("qa2tag"));
    expect(r).not.toBeNull();
    expect(r!.fields).toEqual(["tag"]);
    expect(r!.tags).toEqual(["qa2tag"]);
  });

  it("태그 대소문자 무시 + 부분 일치", () => {
    const r = matchNote(target({ tags: ["QA2Tag"] }), q("2ta"));
    expect(r!.tags).toEqual(["QA2Tag"]);
  });

  it("제목·태그·본문이 모두 맞으면 필드 3개를 제목→태그→본문 순으로 반환", () => {
    const r = matchNote(target({ title: "spec", text: "spec 본문", tags: ["spec"] }), q("spec"));
    expect(r!.fields).toEqual(["title", "tag", "body"]);
  });

  it("어느 필드도 안 맞으면 null", () => {
    expect(matchNote(target({ title: "회의", text: "본문", tags: ["기획"] }), q("zzz"))).toBeNull();
  });

  it("빈 질의는 null (목록 모드는 호출측 책임)", () => {
    expect(matchNote(target({ title: "회의" }), q(""))).toBeNull();
  });

  it("#태그 질의는 제목·본문이 맞아도 태그가 안 맞으면 제외", () => {
    expect(matchNote(target({ title: "qa2tag", text: "qa2tag", tags: ["다른것"] }), q("#qa2tag"))).toBeNull();
  });

  it("#태그 질의에서 정확히 일치하는 태그가 부분 일치보다 높은 점수", () => {
    const exact = matchNote(target({ tags: ["qa"] }), q("#qa"))!;
    const partial = matchNote(target({ tags: ["qa2tag"] }), q("#qa"))!;
    expect(exact.score).toBeGreaterThan(partial.score);
  });

  it("제목 매칭이 본문 매칭보다 높은 점수", () => {
    const t = matchNote(target({ title: "spec" }), q("spec"))!;
    const b = matchNote(target({ text: "spec" }), q("spec"))!;
    expect(t.score).toBeGreaterThan(b.score);
  });

  it("태그 매칭이 본문 매칭보다 높은 점수", () => {
    const tg = matchNote(target({ tags: ["spec"] }), q("spec"))!;
    const b = matchNote(target({ text: "spec" }), q("spec"))!;
    expect(tg.score).toBeGreaterThan(b.score);
  });
});

describe("rankMatches", () => {
  const notes = [
    { id: "n1", title: "본문에만 있음", text: "여기 qa2tag 가 있다", tags: [] as string[] },
    { id: "n2", title: "태그만 있음", text: "무관한 본문", tags: ["qa2tag"] },
    { id: "n3", title: "qa2tag 제목", text: "무관", tags: [] as string[] },
    { id: "n4", title: "무관", text: "무관", tags: ["기획"] },
  ];
  const toTarget = (n: (typeof notes)[number]): SearchTarget => ({ title: n.title, text: n.text, tags: n.tags });

  it("제목 > 태그 > 본문 순으로 정렬하고 비매칭은 제외", () => {
    const r = rankMatches(notes, toTarget, "qa2tag");
    expect(r.map((x) => x.item.id)).toEqual(["n3", "n2", "n1"]);
  });

  it("#태그 질의는 태그 보유 노트만 남긴다", () => {
    const r = rankMatches(notes, toTarget, "#qa2tag");
    expect(r.map((x) => x.item.id)).toEqual(["n2"]);
  });

  it("빈 질의는 빈 배열(목록 모드는 호출측이 처리)", () => {
    expect(rankMatches(notes, toTarget, "  ")).toEqual([]);
  });

  it("동점은 입력 순서를 유지한다(안정 정렬)", () => {
    const same = [
      { id: "a", title: "spec", text: "", tags: [] as string[] },
      { id: "b", title: "spec", text: "", tags: [] as string[] },
    ];
    const r = rankMatches(same, (n) => ({ title: n.title, text: n.text, tags: n.tags }), "spec");
    expect(r.map((x) => x.item.id)).toEqual(["a", "b"]);
  });
});
