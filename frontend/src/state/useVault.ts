import { useReducer, useEffect, useState, useRef } from "react";
import { vaultReducer } from "./vaultReducer";
import { LocalStorageRepository } from "../storage/LocalStorageRepository";
import { dedupeIds } from "../lib/tree";
import { newId } from "../lib/id";
import { SEED } from "../seed";
import type { VaultTree, NoteNode, NotePii } from "../types";
import type { VaultRepository } from "../storage/VaultRepository";
import { storageMode } from "../storage";
import { isBackendDown } from "./loadErrorPolicy";

const SAVE_DEBOUNCE = 60000; // persist 1min after the last change (typing pause) — 수동 저장 버튼으로 즉시 저장 가능

const defaultRepo = new LocalStorageRepository();

export function useVault(repo: VaultRepository = defaultRepo) {
  const [tree, dispatch] = useReducer(vaultReducer, null, () => dedupeIds(SEED));
  const [savedTick, setSavedTick] = useState(0); // increments each time a debounced save lands
  const [ready, setReady] = useState(false);
  const [loadError, setLoadError] = useState(false); // http 백엔드 다운 → App 차단 화면
  const firstRef = useRef(true);
  const timerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const treeRef = useRef(tree);
  const readyRef = useRef(false);
  const justLoadedRef = useRef(false);
  treeRef.current = tree;

  // async initial load — replace seed with persisted data if available
  useEffect(() => {
    repo.load().then((saved) => {
      if (saved) {
        justLoadedRef.current = true;
        dispatch({ type: "replace", tree: dedupeIds(saved) });
      }
      readyRef.current = true;
      setReady(true);
    }).catch((e) => {
      console.warn("vault load failed — falling back to seed", e);
      if (isBackendDown(e, storageMode)) setLoadError(true); // http 다운: seed를 '정상'인 양 보여주지 않고 차단
      readyRef.current = true;
      setReady(true); // 시드 트리로 렌더 (차단 화면이 App에서 우선)
    });
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // debounced persistence — only writes once typing pauses
  useEffect(() => {
    if (firstRef.current) { firstRef.current = false; return; } // skip the initial render
    if (justLoadedRef.current) { justLoadedRef.current = false; return; } // skip the initial replace — no spurious save/toast
    if (!readyRef.current) return; // ready 전에는 저장 금지 (시드가 저장본을 덮어쓰지 않도록)
    if (timerRef.current) clearTimeout(timerRef.current);
    timerRef.current = setTimeout(() => {
      void repo.save(treeRef.current);
      setSavedTick((n) => n + 1);
    }, SAVE_DEBOUNCE);
    return () => { if (timerRef.current) clearTimeout(timerRef.current); };
  }, [tree]);

  // never lose data: flush immediately if the tab is hidden or closed
  useEffect(() => {
    const flush = () => {
      if (!readyRef.current) return; // ready 전에는 플러시 금지
      if (timerRef.current) clearTimeout(timerRef.current);
      if (repo instanceof LocalStorageRepository) {
        repo.saveSync(treeRef.current);
      } else {
        void repo.save(treeRef.current);
      }
    };
    const onVis = () => { if (document.visibilityState === "hidden") flush(); };
    window.addEventListener("beforeunload", flush);
    document.addEventListener("visibilitychange", onVis);
    return () => { window.removeEventListener("beforeunload", flush); document.removeEventListener("visibilitychange", onVis); };
  }, []);

  // bound action creators (command-ish API)
  const actions = {
    toggle: (id: string) => dispatch({ type: "toggle", id }),
    open: (id: string) => dispatch({ type: "open", id }),
    collapseAll: () => dispatch({ type: "collapseAll" }),
    rename: (id: string, value: string) => dispatch({ type: "rename", id, value }),
    remove: (id: string) => dispatch({ type: "remove", id }),
    move: (id: string, parentId: string | null) => dispatch({ type: "move", id, parentId }),
    updateNote: (id: string, patch: Partial<NoteNode>) => dispatch({ type: "updateNote", id, patch }),
    setNotePii: (id: string, pii: NotePii | null) => dispatch({ type: "setNotePii", id, pii }),
    addNote: (folderId: string | null) => {
      const now = new Date().toISOString();
      const node = { id: newId(), type: "note" as const, title: "제목 없는 노트", tags: [] as string[], updated: now.slice(0, 10), created: now, content: "" };
      dispatch({ type: "insert", folderId, node });
      return node;
    },
    addFolder: (folderId: string | null) => {
      const node = { id: newId(), type: "folder" as const, name: "새 폴더", open: true, created: new Date().toISOString(), children: [] as VaultTree };
      dispatch({ type: "insert", folderId, node });
      return node;
    },
    // 서버 상태로 트리 재동기화 — 휴지통 복구 등 외부 변경 후 호출(초기 load와 동일 경로).
    reload: () => {
      void repo.load().then((saved) => {
        if (saved) dispatch({ type: "replace", tree: dedupeIds(saved) });
      }).catch((e) => console.warn("vault reload failed", e));
    },
  };

  // 수동 저장 — 디바운스 타이머를 건너뛰고 즉시 persist (local 모드 localStorage 쓰기).
  // savedTick은 올리지 않는다(수동 저장 피드백은 App에서 직접 토스트).
  const saveNow = () => {
    if (!readyRef.current) return;
    if (timerRef.current) clearTimeout(timerRef.current);
    if (repo instanceof LocalStorageRepository) repo.saveSync(treeRef.current);
    else void repo.save(treeRef.current);
  };

  return { tree, actions, savedTick, ready, loadError, saveNow };
}
