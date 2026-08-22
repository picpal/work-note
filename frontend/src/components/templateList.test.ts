// @vitest-environment jsdom
// jsdom이 필요한 건 renderMarkdown 렌더링 검증 테스트뿐이지만(DOM API 사용),
// 이 파일의 나머지 순수 로직 테스트는 jsdom에서도 그대로 통과한다.
import { describe, it, expect } from "vitest";
import { groupTemplates, canEdit, wrapForInsert } from "./templateList";
import { renderMarkdown } from "../lib/markdown";
import type { ApiTemplate } from "../api/templates";

const t = (id: string, name: string, system: boolean): ApiTemplate => ({ id, name, body: "## " + name, system });

describe("groupTemplates", () => {
  it("시스템과 개인을 분리한다", () => {
    const g = groupTemplates([t("1", "회의록", true), t("2", "내 양식", false)]);
    expect(g.system.map((x) => x.id)).toEqual(["1"]);
    expect(g.mine.map((x) => x.id)).toEqual(["2"]);
  });

  it("각 그룹을 이름 오름차순으로 정렬한다", () => {
    const g = groupTemplates([
      t("1", "하양식", true), t("2", "가양식", true),
      t("3", "다양식", false), t("4", "나양식", false),
    ]);
    expect(g.system.map((x) => x.name)).toEqual(["가양식", "하양식"]);
    expect(g.mine.map((x) => x.name)).toEqual(["나양식", "다양식"]);
  });

  it("입력 배열을 변형하지 않는다", () => {
    const input = [t("1", "하", true), t("2", "가", true)];
    groupTemplates(input);
    expect(input.map((x) => x.id)).toEqual(["1", "2"]);
  });

  it("빈 목록도 빈 그룹을 돌려준다", () => {
    expect(groupTemplates([])).toEqual({ system: [], mine: [] });
  });
});

describe("canEdit", () => {
  it("시스템 템플릿은 사용자 모달에서 수정 불가", () => {
    expect(canEdit(t("1", "회의록", true))).toBe(false);
  });
  it("내 템플릿은 수정 가능", () => {
    expect(canEdit(t("2", "내 양식", false))).toBe(true);
  });
});

// Regression: 본문이 있는 노트에서 템플릿을 커서 위치(줄 중간)에 삽입하면 "## 헤딩"이
// 직전 줄 텍스트에 그대로 붙어(예: "확인## 이번 주 진행") 마크다운이 깨졌다 (/qa 2026-08-23 발견).
//
// codex 2회차 리뷰(B1): 위 회귀를 고치며 body를 trim()해 앞뒤 개행 하나씩만 남기는 수정이
// 들어갔는데, 개행 하나로는 GFM 표/목록을 못 끊어 삽입 블록 뒤 기존 텍스트가 표 행/목록
// 항목으로 흡수되고, 선행 들여쓰기(들여쓴 코드 블록·중첩 목록)가 trim에 잘려나갔다.
// 아래는 그 4가지 요구 속성을 각각 못 박는다.
describe("wrapForInsert", () => {
  it("본문 앞뒤에 개행을 감싸 커서 주변 내용과 줄이 섞이지 않게 한다(원래 회귀)", () => {
    expect(wrapForInsert("## 이번 주 진행\n-\n")).toBe("\n\n## 이번 주 진행\n-\n\n");
  });

  it("빈 문자열도 개행만 감싼 값을 돌려준다", () => {
    expect(wrapForInsert("")).toBe("\n\n\n\n");
  });

  // 속성 3: 선행 들여쓰기 보존 — 들여쓴 코드 블록의 4-space 인덴트가 살아 있어야
  // 일반 문단으로 붕괴하지 않는다. trim()이었다면 앞의 공백이 통째로 사라졌다.
  it("본문 선행 들여쓰기는 보존한다(뒤쪽 공백만 정리)", () => {
    expect(wrapForInsert("    console.log('x')\n동작 확인\n")).toBe(
      "\n\n    console.log('x')\n동작 확인\n\n",
    );
    expect(wrapForInsert("  ## 제목\n내용  \n\n")).toBe("\n\n  ## 제목\n내용\n\n");
  });

  // 속성 4: 본문이 개행으로 끝나든, 트레일링 공백/개행이 여러 개든 결과의 뒤쪽 빈 줄 수는 같다.
  it("본문 자체의 trailing 개행 수와 무관하게 결과 뒤쪽 빈 줄 수가 같다", () => {
    const wrapped = "\n\n## 헤딩\n\n";
    expect(wrapForInsert("## 헤딩")).toBe(wrapped);
    expect(wrapForInsert("## 헤딩\n")).toBe(wrapped);
    expect(wrapForInsert("## 헤딩\n\n\n")).toBe(wrapped);
    expect(wrapForInsert("## 헤딩  \n \n")).toBe(wrapped);
  });

  // 속성 1: 커서 앞 기존 내용과 블록 단위로 분리 — 줄 중간 커서(체크박스 항목 텍스트) 뒤에
  // 표로 시작하는 템플릿을 넣어도, 개행 하나가 아니라 완전한 빈 줄로 갈라져 표가 앞 줄의
  // lazy continuation으로 흡수되지 않는다.
  it("줄 중간 커서 앞 기존 내용과 완전한 빈 줄로 분리된다", () => {
    const before = "- [ ] 배포 확인";
    const tableBody = "| 항목 | 담당 |\n|---|---|\n| 배포 | 이도경 |\n";
    const full = before + wrapForInsert(tableBody);
    expect(full).toBe("- [ ] 배포 확인\n\n| 항목 | 담당 |\n|---|---|\n| 배포 | 이도경 |\n\n");
  });

  // 속성 2: 커서 뒤 기존 내용과도 블록 단위로 분리 — 표로 끝나는 템플릿 뒤에 기존 텍스트가
  // 개행 없이 바로 이어져 있어도, 완전한 빈 줄이 있어 표의 다음 행으로 흡수되지 않는다.
  it("표로 끝나는 템플릿 뒤 기존 내용과 완전한 빈 줄로 분리된다", () => {
    const tableBody = "| 항목 | 담당 |\n|---|---|\n| 배포 | 이도경 |\n";
    const after = "다음 할 일 목록입니다.";
    const full = wrapForInsert(tableBody) + after;
    expect(full).toBe("\n\n| 항목 | 담당 |\n|---|---|\n| 배포 | 이도경 |\n\n다음 할 일 목록입니다.");
  });
});

// 위 문자열 속성이 실제 렌더링에서도 성립하는지 HTML 수준에서 확인한다(이 결함의 본질).
describe("wrapForInsert × renderMarkdown(실제 렌더링)", () => {
  it("표로 끝나는 템플릿 삽입 뒤 기존 텍스트가 표의 행으로 흡수되지 않는다", () => {
    const tableBody = "| 항목 | 담당 |\n|---|---|\n| 배포 | 이도경 |\n";
    const after = "다음 할 일 목록입니다.";
    const doc = wrapForInsert(tableBody) + after;
    const html = renderMarkdown(doc);

    // 표는 헤더 행 1개 + 데이터 행 1개 = <tr> 2개뿐이어야 한다. 흡수되면 "다음 할 일..."이
    // 세 번째 데이터 행으로 붙어 3개가 된다.
    expect((html.match(/<tr>/g) || []).length).toBe(2);
    // 뒤따르는 텍스트는 표 밖의 독립된 문단으로 렌더된다.
    expect(html).toContain("<p>다음 할 일 목록입니다.</p>");
    // 표 셀(<td>) 안에는 뒤따르는 텍스트가 들어가 있지 않다.
    expect(html).not.toMatch(/<td>[^<]*다음 할 일 목록/);
  });

  it("문단 뒤 삽입한 들여쓴 코드 블록이 앞 문단의 이어지는 줄로 흡수되지 않는다", () => {
    // 들여쓴 코드 블록은 문단을 끊을 수 없다(CommonMark lazy continuation) — 앞뒤 모두
    // 완전한 빈 줄로 갈라야 코드 블록이 문단 텍스트로 붕괴하지 않는다(속성 1+3 조합).
    const before = "지난 회의 결정 사항";
    const codeBody = "    KEY=value\n";
    const doc = before + wrapForInsert(codeBody);
    const html = renderMarkdown(doc);

    expect(html).toContain("<pre>");
    expect(html).not.toMatch(/지난 회의 결정 사항[^<]*KEY=value/);
  });
});
