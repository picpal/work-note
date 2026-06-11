/* App — composition root. Wires vault (reducer), settings, context menu, search,
   editor toolbar, export commands, toasts. */
import { useState, useEffect, useRef, useMemo, useCallback, createElement, Fragment } from "react";
import React from "react";
import { Icon } from "./components/Icon";
import { Sidebar } from "./components/Sidebar";
import { Editor } from "./components/Editor";
import { SearchModal } from "./components/SearchModal";
import { ContextMenu } from "./components/ContextMenu";
import { Outline } from "./components/Outline";
import { ProfileModal } from "./components/ProfileModal";
import { SettingsModal } from "./components/SettingsModal";
import { useVault } from "./state/useVault";
import { repository } from "./storage";
import { usePersist } from "./state/usePersist";
import { useContextMenu } from "./state/useContextMenu";
import { useSettings } from "./state/useSettings";
import { findNode, flattenNotes } from "./lib/tree";
import { setMermaidTheme } from "./lib/markdown";
import { newId } from "./lib/id";
import { exportCommands } from "./commands/exportCommands";
import { SEED_DEFAULT_TITLE } from "./seed";
import type { ToolbarHandlers } from "./components/Editor";

// editor toolbar definition (velog base + diagrams/table/checklist)
const TB_GROUPS: Array<Array<{ k: string; cap?: string; icon?: string; title?: string; fn: (h: ToolbarHandlers) => void }>> = [
  [{ k: "h1", cap: "H1", fn: (h) => h.h(1) }, { k: "h2", cap: "H2", fn: (h) => h.h(2) },
   { k: "h3", cap: "H3", fn: (h) => h.h(3) }, { k: "h4", cap: "H4", fn: (h) => h.h(4) }],
  [{ k: "bold", icon: "bold", title: "굵게", fn: (h) => h.bold() },
   { k: "italic", icon: "italic", title: "기울임", fn: (h) => h.italic() },
   { k: "strike", icon: "strike", title: "취소선", fn: (h) => h.strike() }],
  [{ k: "quote", icon: "quote", title: "인용", fn: (h) => h.quote() },
   { k: "list", icon: "list", title: "목록", fn: (h) => h.list() },
   { k: "checklist", icon: "checklist", title: "체크리스트", fn: (h) => h.checklist() },
   { k: "table", icon: "table", title: "표", fn: (h) => h.table() }],
  [{ k: "link", icon: "link", title: "링크", fn: (h) => h.link() },
   { k: "image", icon: "image", title: "이미지", fn: (h) => h.image() },
   { k: "code", icon: "code", title: "코드 블록", fn: (h) => h.code() }],
  [{ k: "mermaid", icon: "mermaid", title: "Mermaid 다이어그램", fn: (h) => h.mermaid() },
   { k: "sequence", icon: "sequence", title: "시퀀스 다이어그램", fn: (h) => h.sequence() }],
];

export function App() {
  const { tree, actions, savedTick, ready } = useVault(repository);
  const { settings, set } = useSettings();
  const [activeId, setActiveId] = usePersist<string | null>("wn.activeId", null);
  const [collapsed, setCollapsed] = usePersist<boolean>("wn.sbCollapsed", false);
  const [renamingId, setRenamingId] = useState<string | null>(null);
  const [searchOpen, setSearchOpen] = useState(false);
  const [profileOpen, setProfileOpen] = useState(false);
  const [settingsOpen, setSettingsOpen] = useState(false);
  const [toasts, setToasts] = useState<Array<{ id: string; msg: string; icon?: string }>>([]);
  const { menu, openMenu, closeMenu } = useContextMenu();
  const toolbarRef = useRef<ToolbarHandlers>({} as ToolbarHandlers);
  const editorViewRef = useRef<any>(null);
  const currentEmp = (function () { try { return sessionStorage.getItem("wn.session") || "S2019-0007"; } catch (e) { return "S2019-0007"; } })();

  // ---- theme ----
  useEffect(() => {
    const mode = settings.dark ? "dark" : "light";
    document.documentElement.setAttribute("data-theme", mode);
    try { localStorage.setItem("wn.theme", mode); } catch (e) {}
    setMermaidTheme(!!settings.dark);
  }, [settings.dark]);

  // ---- resolve active note + breadcrumbs ----
  const found = useMemo(() => (activeId ? findNode(tree, activeId) : { node: null, path: [] }), [tree, activeId]);
  const activeNote = found.node && found.node.type === "note" ? found.node : null;

  // open default note on first load (after ready — seed→saved replacement may change nodes)
  useEffect(() => {
    if (!ready) return;
    if (activeId && findNode(tree, activeId).node) return;
    const all = flattenNotes(tree);
    const def = all.find((n) => n.note.title === SEED_DEFAULT_TITLE) || all[0];
    if (def) setActiveId(def.note.id);
  }, [ready]);

  // ---- toasts ----
  const toast = useCallback((msg: string, icon?: string) => {
    const id = newId();
    setToasts((ts) => [...ts, { id, msg, icon }]);
    setTimeout(() => setToasts((ts) => ts.filter((x) => x.id !== id)), 2400);
  }, []);

  // ---- save notification (fires when a debounced save lands) ----
  useEffect(() => {
    if (savedTick > 0) toast("저장되었습니다", "check");
  }, [savedTick]);

  // ---- note ops ----
  const openNote = useCallback((note: { id: string }) => { setActiveId(note.id); }, []);
  const newNoteIn = (folderId: string | null) => { const n = actions.addNote(folderId); setActiveId(n.id); setRenamingId(n.id); };
  const newFolderIn = (folderId: string | null) => { const n = actions.addFolder(folderId); setRenamingId(n.id); };
  const onRenameCommit = (id: string, value: string | null) => {
    if (value != null && value.trim() !== "") actions.rename(id, value.trim());
    setRenamingId(null);
  };
  const removeNode = (id: string) => {
    actions.remove(id);
    if (id === activeId) setActiveId(null);
    const { node } = findNode(tree, id);
    toast((node && node.type === "folder" ? "폴더" : "노트") + "를 삭제했습니다", "trash");
  };

  // ---- context menu builders ----
  const exportSub = (note: any) => exportCommands.map((c) => ({
    icon: c.icon, label: c.label, onClick: () => c.run(note, { openNote, toast }),
  }));
  const onContext = (x: number, y: number, node: any) => {
    let items;
    if (!node) {
      items = [
        { icon: "newNote", label: "새 노트", onClick: () => newNoteIn(null) },
        { icon: "folderPlus", label: "새 폴더", onClick: () => newFolderIn(null) },
      ];
    } else if (node.type === "folder") {
      items = [
        { icon: "newNote", label: "새 노트", onClick: () => newNoteIn(node.id) },
        { icon: "folderPlus", label: "새 폴더", onClick: () => newFolderIn(node.id) },
        { sep: true },
        { icon: "edit", label: "이름 변경", onClick: () => setRenamingId(node.id) },
        { icon: "trash", label: "삭제", danger: true, onClick: () => removeNode(node.id) },
      ];
    } else {
      items = [
        { icon: "export", label: "내보내기", submenu: exportSub(node) },
        { sep: true },
        { icon: "edit", label: "이름 변경", onClick: () => setRenamingId(node.id) },
        { icon: "trash", label: "삭제", danger: true, onClick: () => removeNode(node.id) },
      ];
    }
    openMenu(x, y, items);
  };

  // ---- global shortcuts ----
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if ((e.metaKey || e.ctrlKey) && e.key.toLowerCase() === "k") { e.preventDefault(); setSearchOpen((o) => !o); }
      if ((e.metaKey || e.ctrlKey) && e.key === "\\") { e.preventDefault(); setCollapsed((c) => !c); }
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, []);

  // ---- settings-driven CSS vars + classes ----
  const dens = settings.density === "compact" ? { h: 26, fs: 13 } : settings.density === "spacious" ? { h: 34, fs: 14.5 } : { h: 30, fs: 13.5 };
  const appStyle = {
    "--sidebar-w": (settings.sidebarWidth || 264) + "px",
    "--reading-size": (settings.fontSize || 16) + "px",
    "--row-h": dens.h + "px",
    "--row-fs": dens.fs + "px",
  } as React.CSSProperties;
  const appClass = "app" + (collapsed ? " sb-collapsed" : "") + (settings.guides ? " guides" : "") + (settings.showIcons ? "" : " no-icons");

  const callTb = (fn: (h: ToolbarHandlers) => void) => fn(toolbarRef.current);

  if (!ready) return null;

  return createElement(
    "div", { className: appClass, style: appStyle },
    createElement(Sidebar, {
      tree, brand: "WorkNote", activeId, renamingId,
      onOpenSearch: () => setSearchOpen(true),
      onNewNote: newNoteIn, onNewFolder: newFolderIn,
      onCollapseAll: actions.collapseAll,
      onToggleSidebar: () => setCollapsed((c) => !c),
      onToggle: actions.toggle, onOpen: openNote, onContext,
      onRename: setRenamingId, onRenameCommit,
      onSettings: () => setSettingsOpen(true),
      onLogout: () => { try { sessionStorage.removeItem("wn.session"); } catch (e) {} location.href = "login.html"; },
    }),
    createElement(
      "div", { className: "main" },
      // topbar
      createElement(
        "div", { className: "topbar" },
        collapsed && createElement("button", { className: "icon-btn", title: "사이드바 펼치기", onClick: () => setCollapsed(false) },
          createElement(Icon, { name: "panelLeft" })),
        createElement(
          "div", { className: "crumbs" },
          activeNote && createElement("span", { className: "crumb-ic" }, createElement(Icon, { name: (found.path || []).length ? "folder" : "fileLines" })),
          (found.path || []).map((seg, i) =>
            createElement(Fragment, { key: i },
              createElement("span", { className: "seg" }, seg),
              createElement("span", { className: "sep" }, "/"))),
          activeNote && createElement("span", { className: "seg cur" }, activeNote.title || "제목 없음")
        ),
        createElement(
          "div", { className: "right" },
          createElement("button", { className: "topbar-me", title: "프로필", onClick: () => setProfileOpen(true) },
            createElement("span", { className: "tm-ic" }, createElement(Icon, { name: "user" })),
            createElement("span", { className: "tm-emp" }, currentEmp)),
          createElement("button", { className: "icon-btn", title: settings.dark ? "라이트 모드" : "다크 모드", onClick: () => set("dark", !settings.dark) },
            createElement(Icon, { name: settings.dark ? "sun" : "moon" })),
          activeNote && createElement("button", {
            className: "icon-btn", title: "내보내기",
            onClick: (e: React.MouseEvent<HTMLButtonElement>) => { const r = e.currentTarget.getBoundingClientRect(); openMenu(r.right - 200, r.bottom + 6, exportSub(activeNote)); },
          }, createElement(Icon, { name: "export" }))
        )
      ),
      // editor toolbar
      activeNote && createElement(
        "div", { className: "etoolbar" },
        TB_GROUPS.map((g, gi) =>
          createElement(Fragment, { key: gi },
            gi > 0 && createElement("span", { className: "div" }),
            g.map((b) =>
              createElement("button", {
                key: b.k, className: "tb", title: b.title,
                onMouseDown: (e: React.MouseEvent) => e.preventDefault(),
                onClick: () => callTb(b.fn),
              }, b.cap
                ? createElement("span", { className: "hcap" }, b.cap[0], createElement("sub", null, b.cap.slice(1)))
                : createElement(Icon, { name: b.icon! })))
          ))
      ),
      // document
      createElement(
        "div", { className: "doc-scroll" },
        activeNote
          ? createElement(Editor, {
              key: activeNote.id, note: activeNote, theme: settings.dark ? "dark" : "light",
              onChange: (patch) => actions.updateNote(activeNote.id, patch),
              registerToolbar: (h) => { toolbarRef.current = h; },
              onView: (v) => { editorViewRef.current = v; },
            })
          : createElement(
              "div", { className: "empty-state" },
              createElement("div", { className: "es-inner" },
                createElement("div", { className: "es-icon" }, createElement(Icon, { name: "fileLines" })),
                createElement("h2", null, "열린 노트가 없습니다"),
                createElement("p", null, "사이드바에서 노트를 선택하거나 ⌘K 로 검색하세요")))
      ),
      activeNote && createElement(Outline, {
        key: "ol-" + activeNote.id, content: activeNote.content, title: activeNote.title, viewRef: editorViewRef,
      })
    ),
    // overlays
    searchOpen && createElement(SearchModal, {
      notes: flattenNotes(tree),
      onClose: () => setSearchOpen(false),
      onOpen: openNote,
    }),
    profileOpen && createElement(ProfileModal, {
      emp: currentEmp, role: "운영자",
      onClose: () => setProfileOpen(false),
      toast,
    }),
    settingsOpen && createElement(SettingsModal, { settings, onSet: set, onClose: () => setSettingsOpen(false) }),
    menu && createElement(ContextMenu, { x: menu.x, y: menu.y, items: menu.items, onClose: closeMenu }),
    // toasts
    createElement(
      "div", { className: "toast-wrap" },
      toasts.map((t2) =>
        createElement("div", { className: "toast", key: t2.id },
          t2.icon && createElement(Icon, { name: t2.icon }),
          createElement("span", null, t2.msg)))
    )
  );
}
