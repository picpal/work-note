import { useReducer, useEffect, useState, useRef } from "react";
import { vaultReducer } from "./vaultReducer";
import { LocalStorageRepository } from "../storage/LocalStorageRepository";
import { dedupeIds } from "../lib/tree";
import { newId } from "../lib/id";
import { SEED } from "../seed";
import type { VaultTree, NoteNode } from "../types";
import type { VaultRepository } from "../storage/VaultRepository";

const SAVE_DEBOUNCE = 5000; // persist 5s after the last change (typing pause)

const defaultRepo = new LocalStorageRepository();

export function useVault(repo: VaultRepository = defaultRepo) {
  const [tree, dispatch] = useReducer(vaultReducer, null, () => dedupeIds(SEED));
  const [savedTick, setSavedTick] = useState(0); // increments each time a debounced save lands
  const [ready, setReady] = useState(false);
  const firstRef = useRef(true);
  const timerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const treeRef = useRef(tree);
  const readyRef = useRef(false);
  treeRef.current = tree;

  // async initial load — replace seed with persisted data if available
  useEffect(() => {
    repo.load().then((saved) => {
      if (saved) dispatch({ type: "replace", tree: dedupeIds(saved) });
      readyRef.current = true;
      setReady(true);
    });
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // debounced persistence — only writes once typing pauses
  useEffect(() => {
    if (firstRef.current) { firstRef.current = false; return; } // skip the initial render
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
    updateNote: (id: string, patch: Partial<NoteNode>) => dispatch({ type: "updateNote", id, patch }),
    addNote: (folderId: string | null) => {
      const node = { id: newId(), type: "note" as const, title: "제목 없는 노트", tags: [] as string[], updated: new Date().toISOString().slice(0, 10), content: "" };
      dispatch({ type: "insert", folderId, node });
      return node;
    },
    addFolder: (folderId: string | null) => {
      const node = { id: newId(), type: "folder" as const, name: "새 폴더", open: true, children: [] as VaultTree };
      dispatch({ type: "insert", folderId, node });
      return node;
    },
  };
  return { tree, actions, savedTick, ready };
}
