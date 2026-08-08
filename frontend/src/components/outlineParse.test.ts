import { describe, it, expect } from "vitest";
import { parseHeadings } from "./outlineParse";

/** 한 줄을 파싱해 헤딩 텍스트만 뽑는다. 헤딩이 아니면 null. */
const textOf = (line: string): string | null => {
  const hs = parseHeadings(line);
  return hs.length ? hs[0].text : null;
};

describe("parseHeadings — ATX 닫는 # 시퀀스 (특성화)", () => {
  it("본문 중간의 #는 텍스트로 남고 마지막 닫는 #만 제거된다", () => {
    expect(textOf("## foo # bar #")).toBe("foo # bar");
  });
  it("전부 #인 경우 최소 1글자는 텍스트로 남는다", () => {
    expect(textOf("## ###")).toBe("#");
  });
  it("공백만 2칸 이상이면 빈 텍스트 헤딩으로 인정된다", () => {
    expect(textOf("##   ")).toBe("");
  });
  it("'## '(공백 1칸)은 헤딩이 아니다", () => {
    expect(textOf("## ")).toBeNull();
  });
  // GFM/CommonMark: 닫는 # 시퀀스는 앞에 공백이 있어야 한다. 'C#'의 #는 닫는 시퀀스가 아니므로
  // 텍스트에 남아야 한다 — 기존 정규식은 이를 잘라내던 버그였고, T4에서 의도적으로 교정했다.
  it("닫는 #는 앞에 공백이 있어야 한다 — 'C#'의 #는 텍스트", () => {
    expect(textOf("## C#")).toBe("C#");
  });
  it("공백이 앞선 닫는 #만 제거 — 'C# #' → 'C#'", () => {
    expect(textOf("## C# #")).toBe("C#");
  });
});

describe("parseHeadings — 기본", () => {
  it("레벨·줄번호·펜스 내부 무시", () => {
    const src = ["# 제목", "본문", "```", "## 코드 안 헤딩", "```", "### 세 번째"].join("\n");
    expect(parseHeadings(src)).toEqual([
      { level: 1, text: "제목", line: 1 },
      { level: 3, text: "세 번째", line: 6 },
    ]);
  });
  it("#이 7개면 헤딩이 아니다", () => {
    expect(textOf("####### 일곱")).toBeNull();
  });
  it("# 뒤에 공백이 없으면 헤딩이 아니다", () => {
    expect(textOf("##제목")).toBeNull();
  });
  it("탭 구분·후행 공백도 트림된다", () => {
    expect(textOf("#\t제목  ")).toBe("제목");
  });
});

describe("parseHeadings — 병리 입력 (ReDoS 회귀)", () => {
  const fast = (line: string) => {
    const t0 = performance.now();
    parseHeadings(line);
    return performance.now() - t0;
  };
  it("긴 본문 한 줄", () => {
    expect(fast("# " + "a".repeat(50_000))).toBeLessThan(50);
  });
  it("'# ' 반복 — 닫는 시퀀스 모호성 유발 입력", () => {
    expect(fast("## " + "# ".repeat(20_000))).toBeLessThan(50);
  });
  // 실제 폭발 지점. 옛 정규식에서 `.+?`와 `\s*#*\s*`가 같은 공백을 두고 경쟁해
  // 공백 1000/2000/4000칸이 각각 0.2s / 1.6s / 12.2s로 3제곱 가깝게 늘었다(실측).
  it("본문 뒤 긴 공백 런 — 다항 백트래킹 유발 입력", () => {
    expect(fast("## 제목" + " ".repeat(4_000) + "끝")).toBeLessThan(50);
  });
});
