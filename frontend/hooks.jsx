/* Custom hooks — vault state (reducer pattern), persistence, context menu, tweaks bridge. */
(function () {
  const { useReducer, useEffect, useState, useCallback, useRef } = React;
  const clone = (t) => JSON.parse(JSON.stringify(t));

  function load(key, fallback) {
    try { const v = localStorage.getItem(key); return v ? JSON.parse(v) : fallback; }
    catch (e) { return fallback; }
  }
  function save(key, value) {
    try { localStorage.setItem(key, JSON.stringify(value)); } catch (e) {}
  }

  // ---- vault reducer (single source of truth for the tree) ----
  function vaultReducer(tree, a) {
    switch (a.type) {
      case "toggle":
        return window.updateNode(tree, a.id, (n) => { n.open = !n.open; });
      case "open":
        return window.updateNode(tree, a.id, (n) => { n.open = true; });
      case "collapseAll": {
        const t = clone(tree);
        window.walkTree(t, (n) => { if (n.type === "folder") n.open = false; });
        return t;
      }
      case "insert":
        return window.insertChild(tree, a.folderId, a.node);
      case "rename":
        return window.updateNode(tree, a.id, (n) => {
          if (n.type === "folder") n.name = a.value; else n.title = a.value;
        });
      case "remove":
        return window.removeNode(tree, a.id);
      case "updateNote":
        return window.updateNode(tree, a.id, (n) => {
          Object.assign(n, a.patch);
          n.updated = new Date().toISOString().slice(0, 10);
        });
      case "replace":
        return clone(a.tree);
      default:
        return tree;
    }
  }

  const VKEY = "wn.vault.v1";
  const SAVE_DEBOUNCE = 5000; // persist 5s after the last change (typing pause)

  function useVault() {
    const [tree, dispatch] = useReducer(vaultReducer, null, () => window.dedupeIds(load(VKEY, window.SEED)));
    const [savedTick, setSavedTick] = useState(0); // increments each time a debounced save lands
    const firstRef = useRef(true);
    const timerRef = useRef(0);
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
      toggle: (id) => dispatch({ type: "toggle", id }),
      open: (id) => dispatch({ type: "open", id }),
      collapseAll: () => dispatch({ type: "collapseAll" }),
      rename: (id, value) => dispatch({ type: "rename", id, value }),
      remove: (id) => dispatch({ type: "remove", id }),
      updateNote: (id, patch) => dispatch({ type: "updateNote", id, patch }),
      addNote: (folderId) => {
        const node = { id: window.newId(), type: "note", title: "제목 없는 노트", tags: [], updated: new Date().toISOString().slice(0, 10), content: "" };
        dispatch({ type: "insert", folderId, node });
        return node;
      },
      addFolder: (folderId) => {
        const node = { id: window.newId(), type: "folder", name: "새 폴더", open: true, children: [] };
        dispatch({ type: "insert", folderId, node });
        return node;
      },
    };
    return { tree, actions, savedTick };
  }

  // ---- persisted primitive (useState synced to localStorage) ----
  function usePersist(key, initial) {
    const [v, setV] = useState(() => load(key, initial));
    useEffect(() => { save(key, v); }, [key, v]);
    return [v, setV];
  }

  // ---- context menu state ----
  function useContextMenu() {
    const [menu, setMenu] = useState(null); // { x, y, items }
    const openMenu = useCallback((x, y, items) => setMenu({ x, y, items }), []);
    const closeMenu = useCallback(() => setMenu(null), []);
    return { menu, openMenu, closeMenu };
  }

  Object.assign(window, { useVault, usePersist, useContextMenu, wnLoad: load, wnSave: save });
})();
