/* pendingStore — 서버에 아직 반영되지 않은 노트 편집(디바운스 대기·네트워크 실패)을 localStorage에 미러링.
   세션 만료(401 리다이렉트)·탭 종료·크래시로 편집이 조용히 사라지지 않도록 write-through 후 재로그인 시 복구한다. */
const KEY = "wn.pending.v1";
export type PendingPatch = { title?: string; content?: string; tags?: string[] };

function readAll(): Record<string, PendingPatch> {
  try { return JSON.parse(localStorage.getItem(KEY) || "{}") as Record<string, PendingPatch>; }
  catch { return {}; }
}
function writeAll(map: Record<string, PendingPatch>): void {
  try { localStorage.setItem(KEY, JSON.stringify(map)); }
  catch { /* 용량 초과 등 — 복구는 best-effort, 실패해도 본 동기화는 진행 */ }
}

export function savePending(id: string, patch: PendingPatch): void {
  const map = readAll(); map[id] = patch; writeAll(map);
}
export function clearPending(id: string): void {
  const map = readAll();
  if (id in map) { delete map[id]; writeAll(map); }
}
export function loadPending(): Record<string, PendingPatch> {
  return readAll();
}
export function clearAllPending(): void {
  try { localStorage.removeItem(KEY); } catch { /* 무시 */ }
}
