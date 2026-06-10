/* Sidebar — search trigger, toolbar, recursive accordion file tree */
(function () {
  const { useState, useRef, useEffect } = React;
  const Icon = window.Icon;
  const INDENT = 16;

  function Row(props) {
    const { node, depth, activeId, renamingId, onToggle, onOpen, onContext, onRename, onRenameCommit } = props;
    const isFolder = node.type === "folder";
    const isActive = node.id === activeId;
    const renaming = node.id === renamingId;
    const inputRef = useRef(null);

    useEffect(() => {
      if (renaming && inputRef.current) { inputRef.current.focus(); inputRef.current.select(); }
    }, [renaming]);

    const pad = 6 + depth * INDENT;

    const rowEl = React.createElement(
      "div",
      {
        className: "row" + (isActive ? " active" : ""),
        style: { paddingLeft: pad },
        onClick: () => { if (renaming) return; isFolder ? onToggle(node.id) : onOpen(node); },
        onContextMenu: (e) => { e.preventDefault(); e.stopPropagation(); onContext(e.clientX, e.clientY, node); },
      },
      isFolder
        ? React.createElement("span", { className: "twirl" + (node.open ? " open" : "") }, React.createElement(Icon, { name: "chevron" }))
        : React.createElement("span", { className: "twirl", style: { visibility: "hidden" } }),
      React.createElement("span", { className: "ic" },
        React.createElement(Icon, { name: isFolder ? (node.open ? "folderOpen" : "folder") : "fileLines" })),
      renaming
        ? React.createElement("input", {
            className: "tree-rename", ref: inputRef,
            defaultValue: isFolder ? node.name : node.title,
            onClick: (e) => e.stopPropagation(),
            onKeyDown: (e) => {
              if (e.key === "Enter") onRenameCommit(node.id, e.target.value);
              if (e.key === "Escape") onRenameCommit(node.id, null);
            },
            onBlur: (e) => onRenameCommit(node.id, e.target.value),
          })
        : React.createElement("span", { className: "label" }, isFolder ? node.name : node.title),
      !renaming && isFolder && window.countNotes(node) > 0 &&
        React.createElement("span", { className: "count" }, window.countNotes(node))
    );

    if (!isFolder || !node.open) return rowEl;

    return React.createElement(
      React.Fragment, null,
      rowEl,
      React.createElement(
        "div",
        { className: "children", style: { "--gx": (pad + 7) + "px" } },
        (node.children || []).length === 0
          ? React.createElement("div", { className: "row", style: { paddingLeft: pad + INDENT, color: "var(--text-faint)", fontStyle: "italic", height: 26 } }, "비어 있음")
          : node.children.map((c) =>
              React.createElement(Row, { key: c.id, ...props, node: c, depth: depth + 1 }))
      )
    );
  }

  function Sidebar(props) {
    const { tree, brand, onOpenSearch, onNewNote, onNewFolder, onCollapseAll, onToggleSidebar } = props;

    return React.createElement(
      "aside", { className: "sidebar" },
      React.createElement(
        "div", { className: "sb-top" },
        React.createElement("div", { className: "brand" },
          React.createElement("div", { className: "brand-mark" }, "W"),
          React.createElement("div", { className: "brand-name" }, brand || "WorkNote")),
        React.createElement("div", { className: "sb-top-actions" },
          React.createElement("button", { className: "icon-btn", title: "검색  ⌘K", onClick: onOpenSearch },
            React.createElement(Icon, { name: "search" })),
          React.createElement("button", { className: "icon-btn", title: "사이드바 접기", onClick: onToggleSidebar },
            React.createElement(Icon, { name: "panelLeft" })))
      ),
      React.createElement(
        "div", { className: "sb-toolbar" },
        React.createElement("button", { className: "icon-btn", title: "새 노트", onClick: () => onNewNote(null) },
          React.createElement(Icon, { name: "newNote" })),
        React.createElement("button", { className: "icon-btn", title: "새 폴더", onClick: () => onNewFolder(null) },
          React.createElement(Icon, { name: "folderPlus" })),
        React.createElement("div", { className: "spacer" }),
        React.createElement("button", { className: "icon-btn", title: "모두 접기", onClick: onCollapseAll },
          React.createElement(Icon, { name: "collapseAll" }))
      ),
      React.createElement(
        "div", {
          className: "tree",
          onContextMenu: (e) => { e.preventDefault(); props.onContext(e.clientX, e.clientY, null); },
        },
        tree.map((n) => React.createElement(Row, { key: n.id, ...props, node: n, depth: 0 }))
      ),
      React.createElement(
        "div", { className: "sb-footer" },
        React.createElement("a", { className: "sb-fbtn", href: "admin.html", title: "관리자 페이지" },
          React.createElement(Icon, { name: "shield" })),
        React.createElement(
          "div", { className: "sb-fgroup" },
          React.createElement("button", { className: "sb-fbtn", title: "로그아웃", onClick: () => props.onLogout && props.onLogout() },
            React.createElement(Icon, { name: "logout" })),
          React.createElement("button", { className: "sb-fbtn", title: "설정", onClick: () => props.onSettings && props.onSettings() },
            React.createElement(Icon, { name: "cog" }))))
    );
  }

  window.Sidebar = Sidebar;
})();
