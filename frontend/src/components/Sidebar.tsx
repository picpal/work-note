/* Sidebar — search trigger, toolbar, recursive accordion file tree */
import { useState, useRef, useEffect } from "react";
import React from "react";
import { Icon } from "./Icon";
import { countNotes, folderIconName, sortTreeNodes, type TreeSortKey } from "../lib/tree";
import { piiWarns } from "../lib/pii";
import type { VaultTree, VaultNode, NoteNode } from "../types";

const INDENT = 16;

// 사이드바 정렬 드롭다운 옵션 (옵시디언식). 표시 전용 — 새로고침 시 첫 항목으로 리셋.
const SORT_OPTS: Array<{ key: TreeSortKey; label: string }> = [
  { key: "name-asc", label: "이름 오름차순" },
  { key: "name-desc", label: "이름 내림차순" },
  { key: "created-asc", label: "생성일 오름차순" },
  { key: "created-desc", label: "생성일 내림차순" },
];

interface RowProps {
  node: VaultNode;
  depth: number;
  sortKey: TreeSortKey;
  activeId: string | null;
  renamingId: string | null;
  onToggle: (id: string) => void;
  onOpen: (note: NoteNode) => void;
  onContext: (x: number, y: number, node: VaultNode | null) => void;
  onRename: (id: string) => void;
  onRenameCommit: (id: string, value: string | null) => void;
  draggingId: string | null;
  dragOverId: string | null;            // 폴더 id 또는 "__ROOT__"
  onNodeDragStart: (id: string, e: React.DragEvent) => void;
  onNodeDragOver: (id: string | null, e: React.DragEvent) => void;
  onNodeDragLeave: (id: string | null) => void;
  onNodeDrop: (id: string | null, e: React.DragEvent) => void;
  onNodeDragEnd: () => void;
}

function Row(props: RowProps): React.ReactElement {
  const { node, depth, activeId, renamingId, onToggle, onOpen, onContext, onRename, onRenameCommit,
    draggingId, dragOverId, onNodeDragStart, onNodeDragOver, onNodeDragLeave, onNodeDrop, onNodeDragEnd } = props;
  const isFolder = node.type === "folder";
  const isActive = node.id === activeId;
  const renaming = node.id === renamingId;
  const inputRef = useRef<HTMLInputElement>(null);
  const rowRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (renaming && inputRef.current) { inputRef.current.focus(); inputRef.current.select(); }
  }, [renaming]);

  // 활성 노트로 진입(검색·브레드크럼·복원)하면 조상 폴더가 펼쳐지며 이 행이 마운트된다 — 그때 보이도록 스크롤.
  // isActive 변화 시에만 — 본문 편집 등 다른 리렌더에서는 스크롤하지 않는다.
  useEffect(() => {
    if (isActive) rowRef.current?.scrollIntoView({ block: "nearest" });
  }, [isActive]);

  const pad = 6 + depth * INDENT;

  // 드롭 타깃은 폴더만 — 폴더일 때만 over/leave/drop 핸들러를 객체로 합친다.
  const dropProps = isFolder
    ? {
        onDragOver: (e: React.DragEvent) => onNodeDragOver(node.id, e),
        onDragLeave: () => onNodeDragLeave(node.id),
        onDrop: (e: React.DragEvent) => { e.stopPropagation(); onNodeDrop(node.id, e); },
      }
    : {};

  const rowEl = React.createElement(
    "div",
    {
      ref: rowRef,
      className: "row" + (isActive ? " active" : "")
        + (isFolder && dragOverId === node.id ? " drop-target" : "")
        + (draggingId === node.id ? " dragging" : ""),
      style: { paddingLeft: pad },
      draggable: !renaming && !(isFolder && depth === 0),
      onClick: () => { if (renaming) return; isFolder ? onToggle(node.id) : onOpen(node as NoteNode); },
      onContextMenu: (e: React.MouseEvent) => { e.preventDefault(); e.stopPropagation(); onContext(e.clientX, e.clientY, node); },
      onDragStart: (e: React.DragEvent) => { e.stopPropagation(); onNodeDragStart(node.id, e); },
      onDragEnd: () => onNodeDragEnd(),
      ...dropProps,
    },
    isFolder
      ? React.createElement("span", { className: "twirl" + ((node as { open?: boolean }).open ? " open" : "") }, React.createElement(Icon, { name: "chevron" }))
      : React.createElement("span", { className: "twirl", style: { visibility: "hidden" } }),
    React.createElement("span", { className: "ic"
      + (!isFolder && piiWarns((node as NoteNode).pii) ? " pii-warn" : "")
      + (!isFolder && (node as NoteNode).pii?.status === "exempted" ? " pii-exempt" : "") },
      React.createElement(Icon, {
        name: isFolder
          ? folderIconName(depth, !!(node as { open?: boolean }).open)
          : piiWarns((node as NoteNode).pii) ? "alert"
          : (node as NoteNode).pii?.status === "exempted" ? "shieldCheck"
          : "fileLines",
      })),
    renaming
      ? React.createElement("input", {
          className: "tree-rename", ref: inputRef,
          defaultValue: isFolder ? (node as { name: string }).name : (node as NoteNode).title,
          onClick: (e: React.MouseEvent) => e.stopPropagation(),
          onKeyDown: (e: React.KeyboardEvent<HTMLInputElement>) => {
            if (e.key === "Enter") onRenameCommit(node.id, (e.target as HTMLInputElement).value);
            if (e.key === "Escape") onRenameCommit(node.id, null);
          },
          onBlur: (e: React.FocusEvent<HTMLInputElement>) => onRenameCommit(node.id, e.target.value),
        })
      : React.createElement("span", { className: "label" }, isFolder ? (node as { name: string }).name : (node as NoteNode).title),
    !renaming && isFolder && countNotes(node as import("../types").FolderNode) > 0 &&
      React.createElement("span", { className: "count" }, countNotes(node as import("../types").FolderNode))
  );

  if (!isFolder || !(node as { open?: boolean }).open) return rowEl;

  return React.createElement(
    React.Fragment, null,
    rowEl,
    React.createElement(
      "div",
      { className: "children", style: { "--gx": (pad + 7) + "px" } as React.CSSProperties },
      ((node as { children?: VaultNode[] }).children || []).length === 0
        ? React.createElement("div", { className: "row", style: { paddingLeft: pad + INDENT, color: "var(--text-faint)", fontStyle: "italic", height: 26 } }, "비어 있음")
        : sortTreeNodes((node as { children?: VaultNode[] }).children || [], props.sortKey).map((c) =>
            React.createElement(Row, { key: c.id, ...props, node: c, depth: depth + 1 }))
    )
  );
}

interface SidebarProps {
  tree: VaultTree;
  brand?: string;
  activeId: string | null;
  renamingId: string | null;
  onToggle: (id: string) => void;
  onOpen: (note: NoteNode) => void;
  onContext: (x: number, y: number, node: VaultNode | null) => void;
  onRename: (id: string) => void;
  onRenameCommit: (id: string, value: string | null) => void;
  onOpenSearch: () => void;
  onCollapseAll: () => void;
  onToggleSidebar: () => void;
  showAdmin?: boolean;   // admin.html 진입 링크 노출 (local 모드 또는 http 모드 관리자)
  showLogout?: boolean;  // 로그아웃 버튼 노출 (http 모드 + 세션 존재)
  onLogout?: () => void;
  onSettings?: () => void;
  showTrash?: boolean;   // 휴지통 버튼 노출 (http 모드 + 세션)
  onTrash?: () => void;
  draggingId: string | null;
  dragOverId: string | null;            // 폴더 id 또는 "__ROOT__"
  onNodeDragStart: (id: string, e: React.DragEvent) => void;
  onNodeDragOver: (id: string | null, e: React.DragEvent) => void;
  onNodeDragLeave: (id: string | null) => void;
  onNodeDrop: (id: string | null, e: React.DragEvent) => void;
  onNodeDragEnd: () => void;
}

export function Sidebar(props: SidebarProps) {
  const { tree, brand, onOpenSearch, onCollapseAll, onToggleSidebar } = props;
  // 정렬: 표시 전용 in-memory 상태 → 새로고침하면 기본(name-asc)으로 복귀
  const [sortKey, setSortKey] = useState<TreeSortKey>("name-asc");
  const [sortOpen, setSortOpen] = useState(false);
  const sortRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (!sortOpen) return;
    const close = (e: MouseEvent) => {
      if (sortRef.current && !sortRef.current.contains(e.target as Node)) setSortOpen(false);
    };
    const onKey = (e: KeyboardEvent) => { if (e.key === "Escape") setSortOpen(false); };
    window.addEventListener("mousedown", close);
    document.addEventListener("keydown", onKey);
    return () => {
      window.removeEventListener("mousedown", close);
      document.removeEventListener("keydown", onKey);
    };
  }, [sortOpen]);

  return React.createElement(
    "aside", { className: "sidebar" },
    React.createElement(
      "div", { className: "sb-top" },
      React.createElement("div", { className: "brand" },
        React.createElement("div", { className: "brand-mark" }, "W"),
        React.createElement("div", { className: "brand-name" }, brand || "WorkNote")),
      React.createElement("div", { className: "sb-top-actions" },
        React.createElement("button", { className: "icon-btn", title: "사이드바 접기", onClick: onToggleSidebar },
          React.createElement(Icon, { name: "panelLeft" })))
    ),
    React.createElement(
      "button", { className: "sb-search", title: "검색  ⌘K", onClick: onOpenSearch },
      React.createElement(Icon, { name: "search" }),
      React.createElement("span", { className: "sb-search-ph" }, "검색…"),
      React.createElement("span", { className: "kbd" }, "⌘K")),
    React.createElement(
      "div", { className: "sb-toolbar" },
      React.createElement("div", { className: "spacer" }),
      // 정렬 (접기 버튼 왼쪽) — 클릭 시 드롭다운, 선택하면 즉시 정렬
      React.createElement(
        "div", { className: "sb-sort", ref: sortRef },
        React.createElement("button", {
          className: "icon-btn" + (sortOpen ? " active" : ""),
          title: "정렬", onClick: () => setSortOpen((v) => !v),
        }, React.createElement(Icon, { name: "sort" })),
        sortOpen && React.createElement(
          "div", { className: "ctx sb-sort-menu" },
          SORT_OPTS.map((opt) =>
            React.createElement("div", {
              key: opt.key,
              className: "ctx-item",
              onClick: () => { setSortKey(opt.key); setSortOpen(false); },
            },
              React.createElement("span", null, opt.label),
              sortKey === opt.key &&
                React.createElement("span", { className: "chev" }, React.createElement(Icon, { name: "check" }))))
        )
      ),
      React.createElement("button", { className: "icon-btn", title: "모두 접기", onClick: onCollapseAll },
        React.createElement(Icon, { name: "collapseAll" }))
    ),
    React.createElement(
      "div", {
        className: "tree",
        onContextMenu: (e: React.MouseEvent) => { e.preventDefault(); props.onContext(e.clientX, e.clientY, null); },
      },
      sortTreeNodes(tree, sortKey).map((n) => React.createElement(Row, { key: n.id, ...props, node: n, depth: 0, sortKey }))
    ),
    React.createElement(
      "div", { className: "sb-footer" },
      props.showAdmin && React.createElement("a", { className: "sb-fbtn", href: "admin.html", title: "관리자 페이지" },
        React.createElement(Icon, { name: "shield" })),
      React.createElement(
        "div", { className: "sb-fgroup" },
        props.showTrash && React.createElement("button", { className: "sb-fbtn", title: "휴지통", onClick: () => props.onTrash && props.onTrash() },
          React.createElement(Icon, { name: "trash" })),
        props.showLogout && React.createElement("button", { className: "sb-fbtn", title: "로그아웃", onClick: () => props.onLogout && props.onLogout() },
          React.createElement(Icon, { name: "logout" })),
        React.createElement("button", { className: "sb-fbtn", title: "설정", onClick: () => props.onSettings && props.onSettings() },
          React.createElement(Icon, { name: "cog" }))))
  );
}
