/* useVaultSync — reducer 액션을 백엔드 API 호출로 동기화하는 데코레이터 훅.
   순수 매핑(syncAction)·부트스트랩 변환(treeToCreateOps)은 테스트 가능한 순수 함수로 분리.
   낙관적 UI: 로컬 상태는 이미 반영됐으므로 실패는 토스트만 — 재시도는 다음 변경에 편승. */
import { useEffect, useMemo, useRef } from "react";
import { VaultApi } from "../storage/VaultApi";
import type { VaultApiType } from "../storage/VaultApi";
import { storageMode, repository } from "../storage";
import { HttpVaultRepository } from "../storage/HttpVaultRepository";
import type { VaultTree } from "../types";
import type { useVault } from "./useVault";

const PATCH_DEBOUNCE = 1500; // 노트별 content/tags PATCH 디바운스 (타이핑 폭주 방지)

type VaultActions = ReturnType<typeof useVault>["actions"];
type ToastFn = (msg: string) => void;
type NotePatch = { content?: string; tags?: string[] };

/** 동기화 연산의 직렬화 가능한 표현 */
export type SyncOp =
  | { kind: "create"; node: { id: string; parentId: string | null; type: "folder" | "note"; name: string; content?: string } }
  | { kind: "rename"; id: string; name: string }
  | { kind: "update"; id: string; content?: string; tags?: string[] } // content/tags 디바운스 대상
  | { kind: "remove"; id: string }
  | { kind: "move"; id: string; parentId: string | null }; // 매핑만 준비 (UI 없음)

/** op → VaultApi 호출 매핑 (순수) */
export async function syncAction(api: VaultApiType, op: SyncOp): Promise<void> {
  switch (op.kind) {
    case "create":
      await api.create(op.node);
      return;
    case "rename":
      await api.update(op.id, { name: op.name });
      return;
    case "update": {
      const patch: NotePatch = {};
      if (op.content !== undefined) patch.content = op.content;
      if (op.tags !== undefined) patch.tags = op.tags;
      await api.update(op.id, patch);
      return;
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

/** 시드 부트스트랩 — HTTP 모드에서 최초 load가 빈 서버(null)였을 때만, 1회, 순차 업로드. */
let bootstrapped = false;
export async function bootstrapIfEmpty(tree: VaultTree, toastFn?: ToastFn): Promise<void> {
  if (bootstrapped) return;
  if (storageMode !== "http") return;
  if (!(repository instanceof HttpVaultRepository) || !repository.wasEmpty) return;
  bootstrapped = true; // 실패해도 재시도하지 않음 — 부분 업로드 중복 방지
  try {
    for (const op of treeToCreateOps(tree)) await syncAction(VaultApi, op); // 순차 — 부모 먼저
  } catch (e) {
    toastFn?.("서버 동기화 실패: " + (e instanceof Error ? e.message : String(e)));
  }
}

/** actions를 동일 시그니처로 데코레이트 — HTTP 모드에서만 서버 동기화를 얹는다. */
export function useVaultSync(actions: VaultActions, toastFn: ToastFn): VaultActions {
  const actionsRef = useRef(actions);
  actionsRef.current = actions;
  const toastRef = useRef(toastFn);
  toastRef.current = toastFn;
  // 노트별 디바운스 타이머 + 누적 patch
  const pendingRef = useRef(new Map<string, { timer: ReturnType<typeof setTimeout>; patch: NotePatch }>());

  // 안정 ref만 닫아두므로 첫 렌더 인스턴스를 useMemo/useEffect가 공유해도 안전
  const fire = (op: SyncOp) => {
    syncAction(VaultApi, op).catch((e: unknown) => {
      toastRef.current("서버 동기화 실패: " + (e instanceof Error ? e.message : String(e)));
    });
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
        fire({ kind: "rename", id, name: value });
      },
      remove: (id) => {
        cancelPending(id); // 삭제된 노트로 늦은 PATCH가 날아가지 않도록
        actionsRef.current.remove(id);
        fire({ kind: "remove", id });
      },
      updateNote: (id, patch) => {
        actionsRef.current.updateNote(id, patch);
        if (patch.content === undefined && patch.tags === undefined) return; // title은 rename 경로 담당
        const prev = pendingRef.current.get(id);
        if (prev) clearTimeout(prev.timer);
        const merged: NotePatch = { ...prev?.patch };
        if (patch.content !== undefined) merged.content = patch.content;
        if (patch.tags !== undefined) merged.tags = patch.tags;
        const timer = setTimeout(() => {
          pendingRef.current.delete(id);
          fire({ kind: "update", id, ...merged });
        }, PATCH_DEBOUNCE);
        pendingRef.current.set(id, { timer, patch: merged });
      },
      addNote: (folderId) => {
        const node = actionsRef.current.addNote(folderId);
        fire({ kind: "create", node: { id: node.id, parentId: folderId, type: "note", name: node.title, content: "" } });
        return node;
      },
      addFolder: (folderId) => {
        const node = actionsRef.current.addFolder(folderId);
        fire({ kind: "create", node: { id: node.id, parentId: folderId, type: "folder", name: node.name } });
        return node;
      },
    };
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // 언마운트 시 pending 타이머 flush — 즉시 발사
  useEffect(() => {
    return () => {
      for (const [id, p] of pendingRef.current) {
        clearTimeout(p.timer);
        fire({ kind: "update", id, ...p.patch });
      }
      pendingRef.current.clear();
    };
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  return storageMode === "http" ? synced : actions;
}
