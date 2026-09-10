/* toastPolicy — 토스트 표시 시간 결정(순수).

   실패·경고 토스트가 유일한 통지 채널인 곳이 많은데(서버 동기화 실패, 업로드 거부, 입력 검증)
   1.5초로는 한국어 한 문장을 읽기 어렵다. 호출부는 수십 곳이라 시그니처를 바꾸지 않고
   "성공은 아이콘을 넘긴다"는 레포 관례를 신호로 쓰고, 새 호출부는 kind를 명시할 수 있게 한다. */

export type ToastKind = "ok" | "warn";

const OK_MS = 1500;
const WARN_MS = 5000;

/** 진행 알림("업로드 중…")은 성공 계열 — 곧 결과 토스트로 대체되므로 오래 남기지 않는다. */
function isProgress(msg: string): boolean {
  return /(…|\.\.\.)\s*$/.test(msg);
}

export function toastKind(msg: string, icon?: string, explicit?: ToastKind): ToastKind {
  if (explicit) return explicit;      // 호출부 명시 우선
  if (icon) return "ok";              // 관례: 성공 토스트만 아이콘을 넘긴다(check/clipboard/trash/…)
  if (isProgress(msg)) return "ok";
  return "warn";
}

export function toastDuration(kind: ToastKind): number {
  return kind === "warn" ? WARN_MS : OK_MS;
}
