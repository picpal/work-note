import type { NotePii } from "../types";

/** 경고를 띄워야 하는 상태(미해결). exempted/none/없음은 비경고. */
export function piiWarns(pii: NotePii | null | undefined): boolean {
  return !!pii && (pii.status === "suspected" || pii.status === "requested" || pii.status === "rejected");
}

const LABELS: Record<string, string> = {
  rrn: "주민등록번호", phone: "휴대폰번호", email: "이메일", card: "신용카드번호",
  biz: "사업자등록번호", passport: "여권번호", driver: "운전면허번호",
};
export function piiTypeLabel(code: string): string {
  return LABELS[code] ?? code;
}
