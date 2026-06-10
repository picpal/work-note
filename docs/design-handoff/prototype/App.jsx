/* App — composition root. Wires vault (reducer), tweaks, context menu, search,
   editor toolbar, export commands, toasts. */
(function () {
  const { useState, useEffect, useRef, useMemo, useCallback } = React;
  const Icon = window.Icon;
  const { Sidebar, Editor, SearchModal, ContextMenu } = window;
  const { useVault, usePersist, useContextMenu, useTweaks } = window;
  const { TweaksPanel, TweakSection, TweakSlider, TweakToggle, TweakRadio } = window;

  const TWEAK_DEFAULTS = /*EDITMODE-BEGIN*/{
    "dark": false,
    "sidebarWidth": 264,
    "density": "comfortable",
    "showIcons": true,
    "guides": true,
    "fontSize": 16
  }/*EDITMODE-END*/;

  // editor toolbar definition (velog base + diagrams/table/checklist)
  const TB_GROUPS = [
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

  function App() {
    const { tree, actions, savedTick } = useVault();
    const [t, setTweak] = useTweaks(TWEAK_DEFAULTS);
    const [activeId, setActiveId] = usePersist("wn.activeId", null);
    const [collapsed, setCollapsed] = usePersist("wn.sbCollapsed", false);
    const [renamingId, setRenamingId] = useState(null);
    const [searchOpen, setSearchOpen] = useState(false);
    const [profileOpen, setProfileOpen] = useState(false);
    const [toasts, setToasts] = useState([]);
    const { menu, openMenu, closeMenu } = useContextMenu();
    const toolbarRef = useRef({});
    const editorViewRef = useRef(null);
    const currentEmp = (function () { try { return sessionStorage.getItem("wn.session") || "S2019-0007"; } catch (e) { return "S2019-0007"; } })();

    // ---- theme ----
    useEffect(() => {
      const mode = t.dark ? "dark" : "light";
      document.documentElement.setAttribute("data-theme", mode);
      try { localStorage.setItem("wn.theme", mode); } catch (e) {}
      if (window.setMermaidTheme) window.setMermaidTheme(!!t.dark);
    }, [t.dark]);

    // ---- resolve active note + breadcrumbs ----
    const found = useMemo(() => (activeId ? window.findNode(tree, activeId) : { node: null, path: [] }), [tree, activeId]);
    const activeNote = found.node && found.node.type === "note" ? found.node : null;

    // open default note on first load
    useEffect(() => {
      if (activeId && window.findNode(tree, activeId).node) return;
      const all = window.flattenNotes(tree);
      const def = all.find((n) => n.note.title === window.SEED_DEFAULT_TITLE) || all[0];
      if (def) setActiveId(def.note.id);
    }, []);

    // ---- toasts ----
    const toast = useCallback((msg, icon) => {
      const id = window.newId();
      setToasts((ts) => [...ts, { id, msg, icon }]);
      setTimeout(() => setToasts((ts) => ts.filter((x) => x.id !== id)), 2400);
    }, []);

    // ---- save notification (fires when a debounced save lands) ----
    useEffect(() => {
      if (savedTick > 0) toast("저장되었습니다", "check");
    }, [savedTick]);

    // ---- note ops ----
    const openNote = useCallback((note) => { setActiveId(note.id); }, []);
    const newNoteIn = (folderId) => { const n = actions.addNote(folderId); setActiveId(n.id); setRenamingId(n.id); };
    const newFolderIn = (folderId) => { const n = actions.addFolder(folderId); setRenamingId(n.id); };
    const onRenameCommit = (id, value) => {
      if (value != null && value.trim() !== "") actions.rename(id, value.trim());
      setRenamingId(null);
    };
    const removeNode = (id) => {
      actions.remove(id);
      if (id === activeId) setActiveId(null);
      const { node } = window.findNode(tree, id);
      toast((node && node.type === "folder" ? "폴더" : "노트") + "를 삭제했습니다", "trash");
    };

    // ---- context menu builders ----
    const exportSub = (note) => window.exportCommands.map((c) => ({
      icon: c.icon, label: c.label, onClick: () => c.run(note, { openNote, toast }),
    }));
    const onContext = (x, y, node) => {
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
      const onKey = (e) => {
        if ((e.metaKey || e.ctrlKey) && e.key.toLowerCase() === "k") { e.preventDefault(); setSearchOpen((o) => !o); }
        if ((e.metaKey || e.ctrlKey) && e.key === "\\") { e.preventDefault(); setCollapsed((c) => !c); }
      };
      window.addEventListener("keydown", onKey);
      return () => window.removeEventListener("keydown", onKey);
    }, []);

    // ---- tweak-driven CSS vars + classes ----
    const dens = t.density === "compact" ? { h: 26, fs: 13 } : t.density === "spacious" ? { h: 34, fs: 14.5 } : { h: 30, fs: 13.5 };
    const appStyle = {
      "--sidebar-w": (t.sidebarWidth || 264) + "px",
      "--reading-size": (t.fontSize || 16) + "px",
      "--row-h": dens.h + "px",
      "--row-fs": dens.fs + "px",
    };
    const appClass = "app" + (collapsed ? " sb-collapsed" : "") + (t.guides ? " guides" : "") + (t.showIcons ? "" : " no-icons");

    const callTb = (fn) => fn(toolbarRef.current);

    return React.createElement(
      "div", { className: appClass, style: appStyle },
      React.createElement(Sidebar, {
        tree, brand: "WorkNote", activeId, renamingId,
        onOpenSearch: () => setSearchOpen(true),
        onNewNote: newNoteIn, onNewFolder: newFolderIn,
        onCollapseAll: actions.collapseAll,
        onToggleSidebar: () => setCollapsed((c) => !c),
        onToggle: actions.toggle, onOpen: openNote, onContext,
        onRename: setRenamingId, onRenameCommit,
        onSettings: () => toast("환경설정은 준비 중입니다", "cog"),
        onLogout: () => { try { sessionStorage.removeItem("wn.session"); } catch (e) {} location.href = "login.html"; },
      }),
      React.createElement(
        "div", { className: "main" },
        // topbar
        React.createElement(
          "div", { className: "topbar" },
          collapsed && React.createElement("button", { className: "icon-btn", title: "사이드바 펼치기", onClick: () => setCollapsed(false) },
            React.createElement(Icon, { name: "panelLeft" })),
          React.createElement(
            "div", { className: "crumbs" },
            activeNote && React.createElement("span", { className: "crumb-ic" }, React.createElement(Icon, { name: (found.path || []).length ? "folder" : "fileLines" })),
            (found.path || []).map((seg, i) =>
              React.createElement(React.Fragment, { key: i },
                React.createElement("span", { className: "seg" }, seg),
                React.createElement("span", { className: "sep" }, "/"))),
            activeNote && React.createElement("span", { className: "seg cur" }, activeNote.title || "제목 없음")
          ),
          React.createElement(
            "div", { className: "right" },
            React.createElement("button", { className: "topbar-me", title: "프로필", onClick: () => setProfileOpen(true) },
              React.createElement("span", { className: "tm-ic" }, React.createElement(Icon, { name: "user" })),
              React.createElement("span", { className: "tm-emp" }, currentEmp)),
            React.createElement("button", { className: "icon-btn", title: t.dark ? "라이트 모드" : "다크 모드", onClick: () => setTweak("dark", !t.dark) },
              React.createElement(Icon, { name: t.dark ? "sun" : "moon" })),
            activeNote && React.createElement("button", {
              className: "icon-btn", title: "내보내기",
              onClick: (e) => { const r = e.currentTarget.getBoundingClientRect(); openMenu(r.right - 200, r.bottom + 6, exportSub(activeNote)); },
            }, React.createElement(Icon, { name: "export" }))
          )
        ),
        // editor toolbar
        activeNote && React.createElement(
          "div", { className: "etoolbar" },
          TB_GROUPS.map((g, gi) =>
            React.createElement(React.Fragment, { key: gi },
              gi > 0 && React.createElement("span", { className: "div" }),
              g.map((b) =>
                React.createElement("button", {
                  key: b.k, className: "tb", title: b.title,
                  onMouseDown: (e) => e.preventDefault(),
                  onClick: () => callTb(b.fn),
                }, b.cap
                  ? React.createElement("span", { className: "hcap" }, b.cap[0], React.createElement("sub", null, b.cap.slice(1)))
                  : React.createElement(Icon, { name: b.icon })))
            ))
        ),
        // document
        React.createElement(
          "div", { className: "doc-scroll" },
          activeNote
            ? React.createElement(Editor, {
                key: activeNote.id, note: activeNote, theme: t.dark ? "dark" : "light",
                onChange: (patch) => actions.updateNote(activeNote.id, patch),
                registerToolbar: (h) => { toolbarRef.current = h; },
                onView: (v) => { editorViewRef.current = v; },
              })
            : React.createElement(
                "div", { className: "empty-state" },
                React.createElement("div", { className: "es-inner" },
                  React.createElement("div", { className: "es-icon" }, React.createElement(Icon, { name: "fileLines" })),
                  React.createElement("h2", null, "열린 노트가 없습니다"),
                  React.createElement("p", null, "사이드바에서 노트를 선택하거나 ⌘K 로 검색하세요")))
        ),
        activeNote && window.Outline && React.createElement(window.Outline, {
          key: "ol-" + activeNote.id, content: activeNote.content, title: activeNote.title, viewRef: editorViewRef,
        })
      ),
      // overlays
      searchOpen && React.createElement(SearchModal, {
        notes: window.flattenNotes(tree),
        onClose: () => setSearchOpen(false),
        onOpen: openNote,
      }),
      profileOpen && window.ProfileModal && React.createElement(window.ProfileModal, {
        emp: currentEmp, role: "운영자",
        onClose: () => setProfileOpen(false),
        toast,
      }),
      menu && React.createElement(ContextMenu, { x: menu.x, y: menu.y, items: menu.items, onClose: closeMenu }),
      // toasts
      React.createElement(
        "div", { className: "toast-wrap" },
        toasts.map((t2) =>
          React.createElement("div", { className: "toast", key: t2.id },
            t2.icon && React.createElement(Icon, { name: t2.icon }),
            React.createElement("span", null, t2.msg)))
      ),
      // tweaks panel
      React.createElement(
        TweaksPanel, { title: "Tweaks" },
        React.createElement(TweakSection, { label: "테마" }),
        React.createElement(TweakToggle, { label: "다크 모드", value: t.dark, onChange: (v) => setTweak("dark", v) }),
        React.createElement(TweakSection, { label: "사이드바" }),
        React.createElement(TweakSlider, { label: "너비", value: t.sidebarWidth, min: 220, max: 360, step: 4, unit: "px", onChange: (v) => setTweak("sidebarWidth", v) }),
        React.createElement(TweakRadio, { label: "밀도", value: t.density, options: ["compact", "comfortable", "spacious"], onChange: (v) => setTweak("density", v) }),
        React.createElement(TweakToggle, { label: "파일 아이콘", value: t.showIcons, onChange: (v) => setTweak("showIcons", v) }),
        React.createElement(TweakToggle, { label: "계층 안내선", value: t.guides, onChange: (v) => setTweak("guides", v) }),
        React.createElement(TweakSection, { label: "본문" }),
        React.createElement(TweakSlider, { label: "글자 크기", value: t.fontSize, min: 14, max: 20, step: 1, unit: "px", onChange: (v) => setTweak("fontSize", v) })
      )
    );
  }

  window.App = App;
})();
