/* auditDetail — audit_log.detail(권한 변경 델타 JSON) → 감사 화면 표시 줄로 변환하는 순수 함수.
   백엔드 AuditDelta가 만드는 계약을 파싱한다:
     acl.set        {"added":[{p,g}],"removed":[{p,g}],"changed":[{p,from,to}]}
     role.update    {"name":{from,to},"caps":{"added":[],"removed":[]}}
     public.*       {"from":<mode|null>,"to":<mode|null>}
   감사 화면은 절대 터지면 안 되므로 모양이 어긋나면 던지지 않고 조용히 건너뛴다(증거는 원문 detail이 DB에 남아 있다). */
import { capLabel } from "./mappers";

export interface AuditDetailLine {
  sign: "+" | "−" | "~";
  text: string;
}

/** ACL grant 라벨 — Permissions 화면과 동일 어휘. 미지 값은 원문 노출(mappers의 데이터 손실 방지 관례). */
const GRANT: Record<string, string> = { read: "읽기", edit: "편집", deny: "거부" };
const grantLabel = (g: unknown): string => (typeof g === "string" ? GRANT[g] ?? g : "?");

/** public 모드 라벨. null/미설정은 "없음" — 되돌림(from→null)이 한눈에 보이게. */
const PUBLIC_MODE: Record<string, string> = { public: "공개", exclude: "제외" };
const modeLabel = (m: unknown): string =>
  m == null ? "없음" : typeof m === "string" ? PUBLIC_MODE[m] ?? m : "?";

const isObj = (v: unknown): v is Record<string, unknown> =>
  typeof v === "object" && v !== null && !Array.isArray(v);
const arr = (v: unknown): unknown[] => (Array.isArray(v) ? v : []);
const str = (v: unknown): string => (typeof v === "string" ? v : "?");

function parse(detail: string | null | undefined): Record<string, unknown> | null {
  if (!detail) return null;
  try {
    const v: unknown = JSON.parse(detail);
    return isObj(v) ? v : null;
  } catch {
    return null;
  }
}

function aclLines(d: Record<string, unknown>): AuditDetailLine[] {
  const out: AuditDetailLine[] = [];
  for (const e of arr(d.added)) {
    if (isObj(e)) out.push({ sign: "+", text: str(e.p) + " " + grantLabel(e.g) });
  }
  for (const e of arr(d.removed)) {
    if (isObj(e)) out.push({ sign: "−", text: str(e.p) + " " + grantLabel(e.g) });
  }
  for (const e of arr(d.changed)) {
    if (isObj(e)) out.push({ sign: "~", text: str(e.p) + " " + grantLabel(e.from) + "→" + grantLabel(e.to) });
  }
  return out;
}

function roleLines(d: Record<string, unknown>): AuditDetailLine[] {
  const out: AuditDetailLine[] = [];
  if (isObj(d.name)) {
    out.push({ sign: "~", text: "이름 " + str(d.name.from) + "→" + str(d.name.to) });
  }
  if (isObj(d.caps)) {
    for (const c of arr(d.caps.added)) out.push({ sign: "+", text: capLabel(str(c)) });
    for (const c of arr(d.caps.removed)) out.push({ sign: "−", text: capLabel(str(c)) });
  }
  return out;
}

function publicLines(d: Record<string, unknown>): AuditDetailLine[] {
  return [{ sign: "~", text: "공개 설정 " + modeLabel(d.from) + "→" + modeLabel(d.to) }];
}

/**
 * 델타 JSON을 표시 줄로 전개. act별 모양이 달라 act를 함께 받는다.
 * 모르는 act는 원문을 한 줄로 보존한다 — 나중에 detail을 쓰는 act가 늘어도 화면에서 사라지지 않게.
 */
export function auditDetailLines(act: string, detail: string | null | undefined): AuditDetailLine[] {
  const d = parse(detail);
  if (!d) return [];
  if (act === "acl.set") return aclLines(d);
  if (act === "role.update") return roleLines(d);
  if (act === "public.set" || act === "public.unset") return publicLines(d);
  return [{ sign: "~", text: detail as string }];
}

/** 펼치기 토글 노출 여부 — 전개 결과가 있을 때만(빈 토글 방지). */
export function hasAuditDetail(act: string, detail: string | null | undefined): boolean {
  return auditDetailLines(act, detail).length > 0;
}
