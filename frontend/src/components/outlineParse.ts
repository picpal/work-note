// 개요(TOC) 헤딩 파서 — 컴포넌트 렌더에서 분리해 순수 함수로 단위 테스트한다.
export interface OutlineHeading {
  level: number;
  text: string;
  line: number;
}

const HASH = 35; // '#'
const WS = /\s/; // 한 글자 판정에만 쓴다(반복자 없음 → 백트래킹 불가)
// JS 정규식의 `.`이 매치하지 못하는 줄 종결자. 옛 패턴의 본문 그룹이 `.+?`였으므로
// 이 문자들은 본문이 될 수 없었고, `\s`에는 포함되므로 꼬리로만 먹혔다.
// 본문은 `\n`으로 잘리니 실물에서는 CRLF 문서의 줄 끝 `\r`이 거의 전부다.
const LT = /[\n\r\u2028\u2029]/;

// ATX 헤딩 한 줄 파싱. 이전 구현은 `/^(#{1,6})\s+(.+?)\s*#*\s*$/` 하나로 처리했는데,
// 게으른 `.+?`와 뒤따르는 `\s*#*\s*`가 같은 문자를 두고 경쟁해 후행 공백이 긴 줄에서
// 백트래킹이 다항 폭발했다(공백 4000칸이면 12초). 인덱스 주사로 풀어 선형으로 만든다.
//
// 옛 패턴을 그대로 옮기면: 해시런 → 공백런(1칸 이상) → 본문(1글자 이상, 줄 종결자 불가)
// → 꼬리(`\s*#*\s*`로 끝까지). 아래는 그 분해점을 정규식 엔진과 같은 순서
// (공백런 최대 → 본문 최소)로 직접 계산한다.
function parseHeading(ln: string): { level: number; text: string } | null {
  const n = ln.length;
  let level = 0;
  while (level < n && ln.charCodeAt(level) === HASH) level++;
  if (level === 0 || level > 6) return null;

  // '#' 뒤에는 공백이 최소 1칸 있어야 헤딩이다 ("##제목", "##"은 헤딩 아님).
  if (level >= n || !WS.test(ln[level])) return null;

  let w = level;
  while (w < n && WS.test(ln[w])) w++;

  // 꼬리가 시작될 수 있는 최소 인덱스 c — 끝에서 공백런·'#'런·공백런 순으로 되짚는다.
  let a = n;
  while (a > level && WS.test(ln[a - 1])) a--;
  let b = a;
  while (b > level && ln.charCodeAt(b - 1) === HASH) b--;
  let c = b;
  while (c > level && WS.test(ln[c - 1])) c--;
  // 닫는 '#' 시퀀스는 GFM/CommonMark상 앞에 공백이 있어야 한다. "C#"의 '#'은 본문이므로
  // 꼬리에서 빼낸다 — 앞 구현이 이를 잘라내 "C"를 내던 것은 버그라 의도적으로 교정했다.
  if (b < a && !WS.test(ln[b - 1])) c = a;

  if (w === n) {
    // 해시 뒤가 전부 공백인 줄. 본문이 1글자 이상이어야 하는데 줄 종결자는 본문이 될 수
    // 없으므로, 종결자가 아닌 공백이 공백런 두 번째 칸 이후에 하나라도 있어야 빈 헤딩이
    // 된다. 그래서 '##  '(공백 2칸)는 빈 헤딩이지만 '## '도 '## \r'도 헤딩이 아니다.
    for (let i = level + 1; i < n; i++) if (!LT.test(ln[i])) return { level, text: "" };
    return null;
  }

  // 본문은 줄 종결자를 넘지 못한다. 넘어야만 꼬리에 닿는 줄이면 매치 자체가 없다.
  let lt = w;
  while (lt < n && !LT.test(ln[lt])) lt++;
  const end = Math.max(w + 1, c); // 본문 최소 1글자
  if (end > lt) return null;

  return { level, text: ln.slice(w, end).trim() };
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
