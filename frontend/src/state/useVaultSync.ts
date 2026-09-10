/* useVaultSync — reducer 액션을 백엔드 API 호출로 동기화하는 데코레이터 훅.
   순수 매핑(syncAction)·부트스트랩 변환(treeToCreateOps)은 테스트 가능한 순수 함수로 분리.
   낙관적 UI: 로컬 상태는 이미 반영됐다. 그래서 전송 실패는 (1) 재전송 큐(pendingOps)에 남기고
   (2) syncState로 화면에 지속 노출한다 — 예전처럼 1.5초 토스트 한 번으로 끝내면
   백엔드가 죽은 동안 만든 노트가 조용히 사라진다(D-2). */
import { useEffect, useMemo, useRef, useState } from "react";
import { VaultApi, ApiError } from "../storage/VaultApi";
import type { VaultApiType } from "../storage/VaultApi";
import { storageMode } from "../storage";
import { savePending, clearPending } from "./pendingStore";
import { enqueueOp, dropOp, loadOps, purgeOpsFor, hasUnsentCreate, shouldRetryStatus, isIdempotentSuccess } from "./pendingOps";
import type { QueuedOp } from "./pendingOps";
import type { FlushResult } from "./syncStatus";
import type { VaultTree, NotePii } from "../types";
import type { useVault } from "./useVault";

const PATCH_DEBOUNCE = 60000; // 노트별 title/content/tags PATCH 디바운스 1min — 수동 저장 버튼(flush)으로 즉시 전송 가능
const RETRY_MS = 10000;       // 재전송 큐가 비어 있지 않은 동안의 재시도 주기(폐쇄망 서버 재기동 대비)

type VaultActions = ReturnType<typeof useVault>["actions"];
type ToastFn = (msg: string, icon?: string) => void;
type UpdatePatch = { name?: string; content?: string; tags?: string[] }; // 서버 PATCH 바디
type MergedPatch = { title?: string; content?: string; tags?: string[] }; // 디바운스 누적 (클라이언트 필드명)

/** 동기화 연산의 직렬화 가능한 표현 */
export type SyncOp =
  | { kind: "create"; node: { id: string; parentId: string | null; type: "folder" | "note"; name: string; content?: string } }
  | { kind: "rename"; id: string; name: string }
  | { kind: "update"; id: string; name?: string; content?: string; tags?: string[] } // title/content/tags 디바운스 대상
  | { kind: "remove"; id: string }
  | { kind: "move"; id: string; parentId: string | null }; // 이동 UI 배선

/** syncAction 결과 — update만 서버 응답({pii})을 돌려주고, 나머지는 void. */
export type SyncResult = { pii?: NotePii } | void;

/** 화면에 노출되는 동기화 상태 — unsynced>0이면 "저장됨"이라고 말하면 안 된다. */
export interface SyncState {
  unsynced: number;      // 재전송 대기 중인 연산 수
  offline: boolean;      // 마지막 실패가 fetch 자체 실패(서버 다운·네트워크 단절)였는가
  lastError: string | null;
}

/** op → VaultApi 호출 매핑 (순수). update는 서버 응답({pii})을 그대로 반환 — Task 11 라이브 반영용. */
export async function syncAction(api: VaultApiType, op: SyncOp): Promise<SyncResult> {
  switch (op.kind) {
    case "create":
      await api.create(op.node);
      return;
    case "rename":
      await api.update(op.id, { name: op.name });
      return;
    case "update": {
      const patch: UpdatePatch = {};
      if (op.name !== undefined) patch.name = op.name;
      if (op.content !== undefined) patch.content = op.content;
      if (op.tags !== undefined) patch.tags = op.tags;
      return await api.update(op.id, patch);
    }
    case "remove":
      await api.trash(op.id);
      return;
    case "move":
      await api.move(op.id, op.parentId);
      return;
  }
}

/** 중첩 트리 → flat create op 리스트. 부모가 자식보다 먼저, 형제는 배열 순서 유지
    (서버 position = max+1 순차 부여라 순서가 곧 position). */
export function treeToCreateOps(tree: VaultTree, parentId: string | null = null): SyncOp[] {
  const ops: SyncOp[] = [];
  for (const n of tree) {
    if (n.type === "folder") {
      ops.push({ kind: "create", node: { id: n.id, parentId, type: "folder", name: n.name } });
      ops.push(...treeToCreateOps(n.children, n.id));
    } else {
      ops.push({ kind: "create", node: { id: n.id, parentId, type: "note", name: n.title, content: n.content } });
    }
  }
  return ops;
}

/** 디바운스 누적 patch → update op. title은 서버 필드명 name으로 변환 (순수). */
export function buildUpdateOp(id: string, merged: MergedPatch): SyncOp {
  const op: Extract<SyncOp, { kind: "update" }> = { kind: "update", id };
  if (merged.title !== undefined) op.name = merged.title;
  if (merged.content !== undefined) op.content = merged.content;
  if (merged.tags !== undefined) op.tags = merged.tags;
  return op;
}

/** 부트스트랩 op 순차 실행 — 409(이미 존재)는 성공으로 간주하고 계속 진행 (멱등). */
export async function runBootstrapOps(api: VaultApiType, ops: SyncOp[]): Promise<void> {
  for (const op of ops) {
    try {
      await syncAction(api, op);
    } catch (e) {
      if (e instanceof ApiError && e.status === 409) continue; // 이미 존재 — skip
      throw e;
    }
  }
}

/** 시드 예제 업로드 — **자동 실행 금지**.
    예전 bootstrapIfEmpty는 "빈 응답"만 보고 자동 업로드해서, 권한이 없어 빈 트리를 받은
    사용자가 시드를 서버에 만들어버렸다(D-1). 지금은 빈 서버임을 확정할 수 있는 관리자가
    화면에서 명시적으로 눌렀을 때만 호출한다(state/emptyVaultPolicy 참고). */
export async function bootstrapSeed(tree: VaultTree): Promise<void> {
  await runBootstrapOps(VaultApi, treeToCreateOps(tree));
}

/** actions를 데코레이트 — HTTP 모드에서만 서버 동기화를 얹는다. flush는 pending PATCH 즉시 전송(수동 저장). */
export function useVaultSync(actions: VaultActions, toastFn: ToastFn): {
  actions: VaultActions;
  flush: () => Promise<FlushResult>;
  syncState: SyncState;
  retryNow: () => void;
} {
  const actionsRef = useRef(actions);
  actionsRef.current = actions;
  const toastRef = useRef(toastFn);
  toastRef.current = toastFn;
  // 노트별 디바운스 타이머 + 누적 patch
  const pendingRef = useRef(new Map<string, { timer: ReturnType<typeof setTimeout>; patch: MergedPatch }>());
  // 재전송 큐 — localStorage 미러(pendingOps)와 항상 같이 움직인다
  const queueRef = useRef<QueuedOp[]>([]);
  const offlineRef = useRef(false);
  const lastErrorRef = useRef<string | null>(null);
  const retryingRef = useRef(false);
  const retryTimerRef = useRef<ReturnType<typeof setInterval> | null>(null);
  const [syncState, setSyncState] = useState<SyncState>({ unsynced: 0, offline: false, lastError: null });

  const publish = () => {
    setSyncState({ unsynced: queueRef.current.length, offline: offlineRef.current, lastError: lastErrorRef.current });
  };

  const errText = (e: unknown) => (e instanceof Error ? e.message : String(e));

  const stopTimer = () => {
    if (retryTimerRef.current) { clearInterval(retryTimerRef.current); retryTimerRef.current = null; }
  };
  const ensureTimer = () => {
    if (!retryTimerRef.current) retryTimerRef.current = setInterval(() => { void retry(); }, RETRY_MS);
  };

  /** 큐 항목 1건 전송 결과 — sent=서버 반영됨, dropped=영구 실패라 폐기, retry=일시 실패(다음 주기). */
  const sendQueued = async (item: QueuedOp): Promise<"sent" | "dropped" | "retry"> => {
    try {
      const res = await syncAction(VaultApi, item.op);
      if (item.op.kind === "update") reflectPii(item.op.id, res);
      queueRef.current = dropOp(item.key);
      offlineRef.current = false;
      lastErrorRef.current = null;
      return "sent";
    } catch (e) {
      const status = e instanceof ApiError ? e.status : null;
      if (isIdempotentSuccess(item.op, status)) {   // 이미 존재 — 멱등 성공
        queueRef.current = dropOp(item.key);
        return "sent";
      }
      if (shouldRetryStatus(status)) {
        offlineRef.current = status === null;
        lastErrorRef.current = errText(e);
        return "retry";
      }
      // 영구 실패 — 무한 재시도를 끊고 사용자에게 알린다
      queueRef.current = dropOp(item.key);
      if (item.op.kind === "update") clearPending(item.op.id);
      toastRef.current("서버에 반영할 수 없는 변경을 취소했습니다: " + errText(e));
      return "dropped";
    }
  };

  /** 큐 재전송 — 순서대로, 첫 실패에서 중단(뒤 연산이 앞 연산에 의존하므로). */
  const retry = async (): Promise<void> => {
    if (retryingRef.current) return;
    if (queueRef.current.length === 0) { stopTimer(); return; }
    retryingRef.current = true;
    let sent = 0;
    try {
      while (queueRef.current.length > 0) {
        const before = queueRef.current.length;
        const r = await sendQueued(queueRef.current[0]);
        if (r === "retry") break;                    // 여전히 실패 — 순서 보존을 위해 뒤 연산은 다음 주기로
        if (r === "sent") sent++;
        if (queueRef.current.length >= before) break; // 방어: 큐가 줄지 않으면 중단(무한 루프 방지)
      }
    } finally {
      retryingRef.current = false;
      if (queueRef.current.length === 0) {
        stopTimer();
        if (sent > 0) toastRef.current("서버와 다시 동기화했습니다 (" + sent + "건)", "check");
      }
      publish();
    }
  };

  /** 실패한 op를 큐에 넣고 상태·타이머를 갱신 */
  const queueFailure = (op: SyncOp, e: unknown, status: number | null) => {
    queueRef.current = enqueueOp(op);
    offlineRef.current = status === null;
    lastErrorRef.current = errText(e);
    publish();
    ensureTimer();
  };

  // 안정 ref만 닫아두므로 첫 렌더 인스턴스를 useMemo/useEffect가 공유해도 안전
  const fire = (op: SyncOp, onSuccess?: (res?: SyncResult) => void): Promise<boolean> =>
    syncAction(VaultApi, op)
      .then((res) => {
        onSuccess?.(res);
        if (queueRef.current.length === 0 && (offlineRef.current || lastErrorRef.current)) {
          offlineRef.current = false; lastErrorRef.current = null; publish();
        }
        return true;
      })
      .catch((e: unknown) => {
        const status = e instanceof ApiError ? e.status : null;
        if (isIdempotentSuccess(op, status)) { onSuccess?.(); return true; } // 이미 존재 — 멱등 성공
        if (shouldRetryStatus(status)) {
          // 일시적 실패(서버 다운·5xx·세션 만료) → 재전송 큐로. 401은 전역 on401이 로그인으로 보내므로 토스트 생략.
          queueFailure(op, e, status);
          if (status !== 401) toastRef.current("서버 저장 실패: " + errText(e) + " — 연결되면 다시 보냅니다");
          return false;
        }
        // 영구 실패(403 권한회수·404 없음 등) — update 미러를 버려 복구 루프를 끊는다.
        if (op.kind === "update") clearPending(op.id);
        toastRef.current(op.kind === "create"
          ? "새 항목을 서버에 만들지 못했습니다: " + errText(e) + " — 새로고침하면 사라집니다"
          : "서버 동기화 실패: " + errText(e));
        return false;
      });

  // PATCH 성공 콜백(디바운스/언마운트 공통): 미러 제거 + PATCH 응답 PII를 로컬 반영(디바운스 비유발).
  // actionsRef.current·clearPending(모듈 import)만 닫으므로 안정 ref 패턴 유지.
  const reflectPii = (id: string, res?: SyncResult) => {
    clearPending(id); // 서버 확정 시에만 미러 제거
    if (res?.pii !== undefined) actionsRef.current.setNotePii(id, res.pii.status === "none" ? null : res.pii);
  };

  const synced = useMemo<VaultActions>(() => {
    const cancelPending = (id: string) => {
      const p = pendingRef.current.get(id);
      if (p) { clearTimeout(p.timer); pendingRef.current.delete(id); }
    };
    return {
      // UI-only — 서버 무관
      toggle: (id) => actionsRef.current.toggle(id),
      open: (id) => actionsRef.current.open(id),
      collapseAll: () => actionsRef.current.collapseAll(),
      rename: (id, value) => {
        actionsRef.current.rename(id, value);
        void fire({ kind: "rename", id, name: value });
      },
      remove: (id) => {
        cancelPending(id);   // 삭제된 노트로 늦은 PATCH가 날아가지 않도록
        clearPending(id);    // 미전송 미러도 함께 폐기
        // 서버에 만들어지지도 못한 노드라면 삭제 요청 자체가 불필요(404) — 대기 연산만 정리하고 끝낸다.
        const unsentCreate = hasUnsentCreate(queueRef.current, id);
        queueRef.current = purgeOpsFor(id);
        publish();
        actionsRef.current.remove(id);
        if (!unsentCreate) void fire({ kind: "remove", id });
      },
      move: (id, parentId) => {
        actionsRef.current.move(id, parentId);
        void fire({ kind: "move", id, parentId });
      },
      updateNote: (id, patch) => {
        actionsRef.current.updateNote(id, patch);
        if (patch.title === undefined && patch.content === undefined && patch.tags === undefined) return;
        const prev = pendingRef.current.get(id);
        if (prev) clearTimeout(prev.timer);
        const merged: MergedPatch = { ...prev?.patch };
        if (patch.title !== undefined) merged.title = patch.title; // flush 때 name으로 변환
        if (patch.content !== undefined) merged.content = patch.content;
        if (patch.tags !== undefined) merged.tags = patch.tags;
        savePending(id, merged);   // write-through — 401/크래시로 끊겨도 미전송분 복구 가능
        const timer = setTimeout(() => {
          pendingRef.current.delete(id);
          void fire(buildUpdateOp(id, merged), (res) => reflectPii(id, res));
        }, PATCH_DEBOUNCE);
        pendingRef.current.set(id, { timer, patch: merged });
      },
      // 순수 로컬 — PATCH 응답 PII 반영 전용. 절대 서버 호출 금지(PATCH 루프 유발).
      setNotePii: (id, pii) => actionsRef.current.setNotePii(id, pii),
      addNote: (folderId) => {
        const node = actionsRef.current.addNote(folderId);
        void fire({ kind: "create", node: { id: node.id, parentId: folderId, type: "note", name: node.title, content: "" } });
        return node;
      },
      addFolder: (folderId) => {
        const node = actionsRef.current.addFolder(folderId);
        void fire({ kind: "create", node: { id: node.id, parentId: folderId, type: "folder", name: node.name } });
        return node;
      },
      reload: () => actionsRef.current.reload(),  // 서버 재동기화 — 동기화 부수효과 없이 위임
    };
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // pending PATCH를 디바운스 대기 없이 즉시 발사 — 수동 저장 버튼·공유 링크 생성·언마운트 공통.
  // 결과를 돌려주므로 호출부가 "저장됐다"를 사실에 근거해 말할 수 있다(D-3).
  const flush = (): Promise<FlushResult> => {
    const jobs: Array<Promise<boolean>> = [];
    for (const [id, p] of pendingRef.current) {
      clearTimeout(p.timer);
      jobs.push(fire(buildUpdateOp(id, p.patch), (res) => reflectPii(id, res)));
    }
    pendingRef.current.clear();
    return Promise.all(jobs).then((rs) => {
      const failed = rs.some((ok) => !ok);
      const unsynced = queueRef.current.length;
      return {
        ok: !failed && unsynced === 0,
        unsynced: unsynced || (failed ? 1 : 0),
        error: lastErrorRef.current ?? undefined,
      };
    });
  };

  // 마운트: 이전 세션에서 못 보낸 연산을 복구해 재전송 (백엔드 재기동·크래시·재로그인 후)
  useEffect(() => {
    if (storageMode !== "http") return;
    queueRef.current = loadOps();
    if (queueRef.current.length > 0) {
      // 이전 세션에서 못 보낸 연산이 있다 = 지금 화면의 트리(방금 받은 GET /tree)에는 그 결과가 없다.
      // 재전송이 끝나면 트리를 다시 받아 화면과 서버를 맞춘다(새로고침 후 노트가 사라져 보이던 D-2).
      publish();
      ensureTimer();
      void retry().then(() => {
        if (queueRef.current.length === 0) actionsRef.current.reload();
      });
    }
    const onOnline = () => { void retry(); };
    window.addEventListener("online", onOnline);
    // 미전송분이 남은 채 탭을 닫으려 하면 경고 — 조용한 유실 방지의 마지막 방어선
    const onBeforeUnload = (e: BeforeUnloadEvent) => {
      if (queueRef.current.length === 0) return;
      e.preventDefault();
      e.returnValue = "";
    };
    window.addEventListener("beforeunload", onBeforeUnload);
    return () => {
      window.removeEventListener("online", onOnline);
      window.removeEventListener("beforeunload", onBeforeUnload);
      stopTimer();
      void flush();   // 언마운트 시 pending 타이머 flush — 즉시 발사
    };
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  return {
    actions: storageMode === "http" ? synced : actions,
    flush,
    syncState,
    retryNow: () => { void retry(); },
  };
}
