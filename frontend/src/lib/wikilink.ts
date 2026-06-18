// 위키링크 파싱·직렬화. 저장형: [[id:<nodeId>|<label>]], 라벨 생략형: [[id:<nodeId>]].
// nodeId는 ']' '|' 공백을 포함하지 않는다. 매칭 실패는 일반 텍스트로 둔다(파괴 금지).

export interface LinkRef {
  id: string;
  label: string; // 파이프 뒤 표시 라벨(없으면 "")
  from: number;
  to: number;
}

// 전역 정규식 — 재사용 시 lastIndex를 0으로 리셋하고 exec 루프를 돈다.
export const WIKILINK_RE = /\[\[id:([^\]|\s]+)(?:\|([^\]]*))?\]\]/g;

export function parseLinks(content: string): LinkRef[] {
  const out: LinkRef[] = [];
  WIKILINK_RE.lastIndex = 0;
  let m: RegExpExecArray | null;
  while ((m = WIKILINK_RE.exec(content || ""))) {
    out.push({ id: m[1], label: m[2] || "", from: m.index, to: m.index + m[0].length });
  }
  return out;
}

export function makeLink(id: string, label: string): string {
  return label ? `[[id:${id}|${label}]]` : `[[id:${id}]]`;
}
