// 개요(TOC) 헤딩 파서 — 컴포넌트 렌더에서 분리해 순수 함수로 단위 테스트한다.
export interface OutlineHeading {
  level: number;
  text: string;
  line: number;
}

const HASH = 35; // '#'
const WS = /\s/; // 한 글자 판정에만 쓴다(반복자 없음 → 백트래킹 불가)

// ATX 헤딩 한 줄 파싱. 이전 구현은 `/^(#{1,6})\s+(.+?)\s*#*\s*$/` 하나로 처리했는데,
// 게으른 `.+?`와 뒤따르는 `\s*#*\s*`가 같은 문자를 두고 경쟁해 후행 공백이 긴 줄에서
// 백트래킹이 다항 폭발했다(공백 4000칸이면 초 단위). 인덱스 주사로 풀어 선형으로 만든다.
function parseHeading(ln: string): { level: number; text: string } | null {
  let level = 0;
  while (level < ln.length && ln.charCodeAt(level) === HASH) level++;
  if (level === 0 || level > 6) return null;

  // '#' 뒤에는 공백이 최소 1칸 있어야 헤딩이다 ("##제목", "##"은 헤딩 아님).
  if (level >= ln.length || !WS.test(ln[level])) return null;

  let s = level;
  while (s < ln.length && WS.test(ln[s])) s++;
  // 공백만 남은 줄: 이전 구현의 `.+?`가 최소 1글자를 요구했기에 '## '(공백 1칸)은 헤딩이
  // 아니고 '##  '(2칸 이상)는 빈 텍스트 헤딩이었다. 이 경계는 그대로 유지한다.
  if (s >= ln.length) return ln.length - level >= 2 ? { level, text: "" } : null;

  let e = ln.length;
  while (e > s && WS.test(ln[e - 1])) e--;

  // 닫는 '#' 시퀀스 제거. GFM/CommonMark는 닫는 시퀀스 앞에 공백을 요구하므로
  // "C#"의 '#'은 텍스트다 — 앞 구현이 이를 잘라내던 것은 버그라 의도적으로 교정했다.
  let h = e;
  while (h > s && ln.charCodeAt(h - 1) === HASH) h--;
  if (h < e && (h === s || WS.test(ln[h - 1]))) {
    // 텍스트가 통째로 '#'이면(h === s) 한 글자는 남긴다 — 옛 `.+?`의 최소 1글자 규칙이
    // 만든 경계라 '## ###' → '#'은 그대로 둔다.
    e = h === s ? s + 1 : h;
    while (e > s && WS.test(ln[e - 1])) e--;
  }

  return { level, text: ln.slice(s, e) };
}

export function parseHeadings(src: string): OutlineHeading[] {
  const lines = (src || "").split("\n");
  let inFence = false;
  const out: OutlineHeading[] = [];
  lines.forEach((ln, i) => {
    if (/^\s*(```|~~~)/.test(ln)) { inFence = !inFence; return; }
    if (inFence) return;
    const h = parseHeading(ln);
    if (h) out.push({ level: h.level, text: h.text, line: i + 1 });
  });
  return out;
}
