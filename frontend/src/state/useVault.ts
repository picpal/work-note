import { useReducer, useEffect, useState, useRef } from "react";
import { vaultReducer } from "./vaultReducer";
import { load, save } from "../storage/local";
import { dedupeIds } from "../lib/tree";
import { newId } from "../lib/id";
import { SEED } from "../seed";
import type { VaultTree } from "../types";

const VKEY = "wn.vault.v1";
const SAVE_DEBOUNCE = 5000; // persist 5s after the last change (typing pause)

export function useVault() {
  const [tree, dispatch] = useReducer(vaultReducer, null, () => dedupeIds(load(VKEY, SEED)));
  const [savedTick, setSavedTick] = useState(0); // increments each time a debounced save lands
  const firstRef = useRef(true);
  const timerRef = useRef<ReturnType<typeof setTimeout>>(0 as unknown as ReturnType<typeof setTimeout>);
  const treeRef = useRef(tree);
  treeRef.current = tree;

  // debounced persistence — only writes once typing pauses
  useEffect(() => {
    if (firstRef.current) { firstRef.current = false; return; } // skip the initial load
    clearTimeout(timerRef.current);
    timerRef.current = setTimeout(() => {
      save(VKEY, treeRef.current);
      setSavedTick((n) => n + 1);
    }, SAVE_DEBOUNCE);
    return () => clearTimeout(timerRef.current);
  }, [tree]);

  // never lose data: flush immediately if the tab is hidden or closed
  useEffect(() => {
    const flush = () => { clearTimeout(timerRef.current); save(VKEY, treeRef.current); };
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
    updateNote: (id: string, patch: Parameters<typeof dispatch>[0] extends { patch: infer P } ? P : never) =>
      dispatch({ type: "updateNote", id, patch }),
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
  return { tree, actions, savedTick };
}
