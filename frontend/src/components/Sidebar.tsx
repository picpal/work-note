/* Sidebar — search trigger, toolbar, recursive accordion file tree */
import { useState, useRef, useEffect } from "react";
import React from "react";
import { Icon } from "./Icon";
import { countNotes, folderIconName, sortTreeNodes, type TreeSortKey } from "../lib/tree";
import { canDropOn } from "../lib/dnd";
import { onSearchRequest } from "./searchBus";
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
  dragOverId: string | null;            // 드롭 하이라이트 중인 폴더 id (루트 하이라이트는 Sidebar 로컬 상태)
  onNodeDragStart: (id: string, e: React.DragEvent) => void;
  onNodeDragOver: (id: string | null, e: React.DragEvent) => void;   // null = 루트(최상위)
  onNodeDragLeave: (id: string | null) => void;
  onNodeDrop: (id: string | null, e: React.DragEvent) => void;       // null = 루트(최상위)
  onNodeDragEnd: () => void;
  onRootOver: (v: boolean) => void;     // 행 위에 있는 동안은 루트 드롭 하이라이트를 끈다
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

  // 드롭 타깃은 폴더만. 단 행 위의 드래그 이벤트는 폴더/노트 가릴 것 없이 전파를 끊는다 —
  // .tree 컨테이너(= 루트 드롭 존)가 행 위 드래그를 "빈 영역"으로 오인하지 않도록.
  const dropProps = {
    onDragOver: (e: React.DragEvent) => {
      e.stopPropagation();
      props.onRootOver(false);
      if (isFolder) onNodeDragOver(node.id, e);
    },
    onDragLeave: (e: React.DragEvent) => { e.stopPropagation(); if (isFolder) onNodeDragLeave(node.id); },
    onDrop: (e: React.DragEvent) => { e.stopPropagation(); if (isFolder) onNodeDrop(node.id, e); },
  };

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
      {
        className: "children",
        style: { "--gx": (pad + 7) + "px" } as React.CSSProperties,
        // 폴더 내부 영역("비어 있음" 포함)은 루트 드롭 존이 아니다 — 전파를 여기서 끊는다.
        onDragOver: (e: React.DragEvent) => { e.stopPropagation(); props.onRootOver(false); },
        onDrop: (e: React.DragEvent) => e.stopPropagation(),
      },
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
  dragOverId: string | null;            // 드롭 하이라이트 중인 폴더 id
  onNodeDragStart: (id: string, e: React.DragEvent) => void;
  onNodeDragOver: (id: string | null, e: React.DragEvent) => void;   // null = 루트(최상위)
  onNodeDragLeave: (id: string | null) => void;
  onNodeDrop: (id: string | null, e: React.DragEvent) => void;       // null = 루트(최상위)
  onNodeDragEnd: () => void;
}

export function Sidebar(props: SidebarProps) {
  const { tree, brand, onOpenSearch, onCollapseAll, onToggleSidebar } = props;
  // 정렬: 표시 전용 in-memory 상태 → 새로고침하면 기본(name-asc)으로 복귀
  const [sortKey, setSortKey] = useState<TreeSortKey>("name-asc");
  const [sortOpen, setSortOpen] = useState(false);
  const sortRef = useRef<HTMLDivElement>(null);
  // 루트(최상위) 드롭 하이라이트 — 폴더 하이라이트(dragOverId)와 달리 사이드바 로컬 상태다.
  // 드롭 payload는 null(=루트)이어야 하므로 App의 dragOverId로는 "루트 위"를 표현할 수 없다.
  const [rootOver, setRootOver] = useState(false);
  const draggingId = props.draggingId;
  const rootDroppable = !!draggingId && canDropOn(tree, draggingId, null);
  useEffect(() => { if (!draggingId) setRootOver(false); }, [draggingId]);

  // 에디터 태그 칩 클릭 → 검색창 열기 (App.tsx가 searchOpen을 쥐고 있어 버스로 받는다).
  // 콜백은 ref로 최신값을 읽어 매 렌더 재구독을 피한다(App이 인라인 화살표를 넘긴다).
  const openSearchRef = useRef(onOpenSearch);
  openSearchRef.current = onOpenSearch;
  useEffect(() => onSearchRequest(() => openSearchRef.current()), []);

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
        className: "tree" + (rootOver ? " drop-root" : ""),
        onContextMenu: (e: React.MouseEvent) => { e.preventDefault(); props.onContext(e.clientX, e.clientY, null); },
        // 빈 영역 드롭 = 루트(최상위)로 이동. 행 위 이벤트는 Row/children이 전파를 끊어 여기 오지 않는다.
        onDragOver: (e: React.DragEvent) => {
          if (!rootDroppable) return;
          e.preventDefault();
          setRootOver(true);
          props.onNodeDragOver(null, e);
        },
        onDragLeave: (e: React.DragEvent) => {
          const rel = e.relatedTarget as Node | null;
          if (rel && (e.currentTarget as HTMLElement).contains(rel)) return; // 내부 이동은 유지
          setRootOver(false);
          props.onNodeDragLeave(null);
        },
        onDrop: (e: React.DragEvent) => {
          setRootOver(false);
          if (!rootDroppable) return;
          props.onNodeDrop(null, e);   // parentId=null → App이 move-preview 경고 경로를 그대로 탄다
        },
      },
      sortTreeNodes(tree, sortKey).map((n) =>
        React.createElement(Row, { key: n.id, ...props, node: n, depth: 0, sortKey, onRootOver: setRootOver })),
      // 드래그 중에만 보이는 루트 드롭 존 — 트리가 화면을 꽉 채워 빈 영역이 없을 때도 놓을 자리를 준다.
      rootDroppable && React.createElement(
        "div", { className: "tree-root-drop" + (rootOver ? " over" : "") },
        React.createElement(Icon, { name: "book" }),
        React.createElement("span", null, "여기에 놓으면 최상위로 이동"))
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
