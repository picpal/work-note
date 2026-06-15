/* Sidebar — search trigger, toolbar, recursive accordion file tree */
import { useState, useRef, useEffect } from "react";
import React from "react";
import { Icon } from "./Icon";
import { countNotes } from "../lib/tree";
import { piiWarns } from "../lib/pii";
import type { VaultTree, VaultNode, NoteNode } from "../types";

const INDENT = 16;

interface RowProps {
  node: VaultNode;
  depth: number;
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

  useEffect(() => {
    if (renaming && inputRef.current) { inputRef.current.focus(); inputRef.current.select(); }
  }, [renaming]);

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
      className: "row" + (isActive ? " active" : "")
        + (isFolder && dragOverId === node.id ? " drop-target" : "")
        + (draggingId === node.id ? " dragging" : ""),
      style: { paddingLeft: pad },
      draggable: !renaming,
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
          ? ((node as { open?: boolean }).open ? "folderOpen" : "folder")
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
        : ((node as { children?: VaultNode[] }).children || []).map((c) =>
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
  onNewNote: (folderId: string | null) => void;
  onNewFolder: (folderId: string | null) => void;
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
        className: "tree" + (props.dragOverId === "__ROOT__" ? " root-drop" : ""),
        onContextMenu: (e: React.MouseEvent) => { e.preventDefault(); props.onContext(e.clientX, e.clientY, null); },
        onDragOver: (e: React.DragEvent) => props.onNodeDragOver(null, e),
        onDragLeave: () => props.onNodeDragLeave(null),
        onDrop: (e: React.DragEvent) => props.onNodeDrop(null, e),
      },
      tree.map((n) => React.createElement(Row, { key: n.id, ...props, node: n, depth: 0 }))
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
