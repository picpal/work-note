/* pendingOps — 서버 전송에 실패한 트리 연산(create/move/rename/remove/update)의 재전송 큐.

   pendingStore(wn.pending.v1)는 "노트 본문 미러"라 create/move는 담지 못한다.
   그래서 백엔드가 죽은 동안 만든 노트가 조용히 사라졌다(D-2). 이 큐는 같은 write-through
   원리를 연산 단위로 확장한 것 — localStorage에 남겨 재연결·재접속 후 순서대로 다시 보낸다.

   병합 규칙(순수):
     · 같은 대상 + 같은 종류 = 최신 것만, 원래 위치 유지 → create가 항상 move/update보다 앞선다.
     · remove는 그 대상의 대기 연산을 모두 정리한다. 서버에 만들지도 못한 노드(create 대기)라면
       삭제 요청도 보낼 필요가 없다(404 유발) — 둘 다 폐기. */
import type { SyncOp } from "./useVaultSync";

const KEY = "wn.pendingops.v1";

export interface QueuedOp {
  key: string;
  op: SyncOp;
}

export function opTargetId(op: SyncOp): string {
  return op.kind === "create" ? op.node.id : op.id;
}

export function opKey(op: SyncOp): string {
  return op.kind + ":" + opTargetId(op);
}

export function mergeQueue(queue: QueuedOp[], op: SyncOp): QueuedOp[] {
  const id = opTargetId(op);
  if (op.kind === "remove") {
    const neverCreated = queue.some((q) => q.key === "create:" + id);
    const rest = queue.filter((q) => opTargetId(q.op) !== id);
    return neverCreated ? rest : [...rest, { key: opKey(op), op }];
  }
  const key = opKey(op);
  const idx = queue.findIndex((q) => q.key === key);
  if (idx >= 0) {
    const next = queue.slice();
    next[idx] = { key, op };   // 위치 보존 = 연산 순서 보존
    return next;
  }
  return [...queue, { key, op }];
}

/** 실패했지만 사실상 성공인가(순수) — create 409는 "이미 존재"라 재전송이 목적을 이미 달성한 상태다.
    재전송 큐는 같은 create를 여러 번 보낼 수 있으므로(전송 성공 응답을 못 받은 경우) 멱등 처리가 필요하다. */
export function isIdempotentSuccess(op: SyncOp, status: number | null): boolean {
  return op.kind === "create" && status === 409;
}

/** 실패한 연산을 재전송 큐에 남길 것인가(순수). status=null은 fetch 자체 실패(백엔드 다운·네트워크 단절). */
export function shouldRetryStatus(status: number | null): boolean {
  if (status === null) return true;              // 서버 다운 — 재기동되면 다시 보낸다
  if (status === 401) return true;               // 세션 만료 — 재로그인 후 다시 보낸다
  if (status === 408 || status === 429) return true;
  return status >= 500;                          // 그 외 4xx(403/404/409/422)는 재시도해도 영원히 실패
}

function readAll(): QueuedOp[] {
  try {
    const raw = localStorage.getItem(KEY);
    const parsed = raw ? (JSON.parse(raw) as unknown) : [];
    return Array.isArray(parsed) ? (parsed as QueuedOp[]) : [];
  } catch {
    return [];   // 깨진 미러가 앱을 못 쓰게 만들지 않는다
  }
}

export function loadOps(): QueuedOp[] {
  return readAll();
}

export function saveOps(queue: QueuedOp[]): void {
  try {
    if (queue.length === 0) localStorage.removeItem(KEY);
    else localStorage.setItem(KEY, JSON.stringify(queue));
  } catch { /* 용량 초과 등 — best-effort */ }
}

/** 큐에 병합 저장하고 병합 후 큐를 반환. */
export function enqueueOp(op: SyncOp): QueuedOp[] {
  const next = mergeQueue(readAll(), op);
  saveOps(next);
  return next;
}

/** 전송에 성공(또는 영구 실패로 폐기)한 op를 큐에서 제거하고 남은 큐를 반환. */
export function dropOp(key: string): QueuedOp[] {
  const next = readAll().filter((q) => q.key !== key);
  saveOps(next);
  return next;
}

/** 노드가 삭제됐을 때 그 노드의 대기 연산을 모두 폐기하고 남은 큐를 반환(404 유발 방지). */
export function purgeOpsFor(id: string): QueuedOp[] {
  const next = readAll().filter((q) => opTargetId(q.op) !== id);
  saveOps(next);
  return next;
}

/** 아직 서버에 만들어지지도 못한 노드인가 — 그렇다면 삭제 요청을 보낼 필요가 없다. */
export function hasUnsentCreate(queue: QueuedOp[], id: string): boolean {
  return queue.some((q) => q.key === "create:" + id);
}

export function clearOps(): void {
  saveOps([]);
}
