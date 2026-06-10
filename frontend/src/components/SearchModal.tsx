/* SearchModal — Cmd+K full-screen overlay, searches title + body. */
import { useState, useEffect, useRef, useMemo } from "react";
import React from "react";
import { Icon } from "./Icon";
import { mdToText } from "../lib/markdown";
import type { NoteNode } from "../types";

const esc = (s: string) => s.replace(/[&<>"]/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;" }[c] as string));

function highlight(text: string, q: string) {
  if (!q) return esc(text);
  const i = text.toLowerCase().indexOf(q.toLowerCase());
  if (i < 0) return esc(text);
  return esc(text.slice(0, i)) + "<mark>" + esc(text.slice(i, i + q.length)) + "</mark>" + esc(text.slice(i + q.length));
}

function snippet(text: string, q: string) {
  if (!q) return esc(text.slice(0, 140));
  const i = text.toLowerCase().indexOf(q.toLowerCase());
  if (i < 0) return esc(text.slice(0, 140));
  const start = Math.max(0, i - 48);
  const slice = (start > 0 ? "…" : "") + text.slice(start, i + q.length + 90);
  return highlight(slice, q);
}

interface SearchModalProps {
  notes: Array<{ note: NoteNode; path: string[] }>;
  onClose: () => void;
  onOpen: (note: NoteNode) => void;
}

export function SearchModal({ notes, onClose, onOpen }: SearchModalProps) {
  const [q, setQ] = useState("");
  const [sel, setSel] = useState(0);
  const inputRef = useRef<HTMLInputElement>(null);
  const listRef = useRef<HTMLDivElement>(null);

  useEffect(() => { inputRef.current && inputRef.current.focus(); }, []);

  const results = useMemo(() => {
    const idx = notes.map((n) => ({ ...n, _text: mdToText(n.note.content) }));
    if (!q.trim()) return idx.slice(0, 30);
    const ql = q.toLowerCase();
    return idx
      .map((n) => {
        const inTitle = n.note.title.toLowerCase().includes(ql);
        const inBody = n._text.toLowerCase().includes(ql);
        if (!inTitle && !inBody) return null;
        return { ...n, score: (inTitle ? 2 : 0) + (inBody ? 1 : 0) };
      })
      .filter(Boolean)
      .sort((a, b) => b!.score - a!.score) as Array<{ note: NoteNode; path: string[]; _text: string; score: number }>;
  }, [q, notes]);

  useEffect(() => { setSel(0); }, [q]);

  const choose = (r: { note: NoteNode } | null | undefined) => { if (r) { onOpen(r.note); onClose(); } };

  const onKey = (e: React.KeyboardEvent) => {
    if (e.key === "ArrowDown") { e.preventDefault(); setSel((s) => Math.min(s + 1, results.length - 1)); }
    else if (e.key === "ArrowUp") { e.preventDefault(); setSel((s) => Math.max(s - 1, 0)); }
    else if (e.key === "Enter") { e.preventDefault(); choose(results[sel]); }
    else if (e.key === "Escape") { e.preventDefault(); onClose(); }
  };

  useEffect(() => {
    const el = listRef.current && listRef.current.querySelector<HTMLElement>(".sr-item.sel");
    if (el && el.offsetParent) listRef.current!.scrollTop = Math.max(0, el.offsetTop - 60);
  }, [sel]);

  return React.createElement(
    "div", { className: "search-overlay", onMouseDown: onClose },
    React.createElement(
      "div", { className: "search-box", onMouseDown: (e: React.MouseEvent) => e.stopPropagation() },
      React.createElement(
        "div", { className: "search-head" },
        React.createElement(Icon, { name: "search" }),
        React.createElement("input", {
          ref: inputRef, value: q, placeholder: "제목 또는 내용 검색…",
          onChange: (e: React.ChangeEvent<HTMLInputElement>) => setQ(e.target.value), onKeyDown: onKey,
        }),
        React.createElement("span", { className: "esc" }, "ESC")
      ),
      React.createElement(
        "div", { className: "search-results", ref: listRef },
        results.length === 0
          ? React.createElement("div", { className: "search-empty" }, "“" + q + "” 와 일치하는 노트가 없습니다")
          : results.map((r, i) =>
              React.createElement(
                "div", {
                  key: r.note.id + "-" + i, className: "sr-item" + (i === sel ? " sel" : ""),
                  onMouseEnter: () => setSel(i), onClick: () => choose(r),
                },
                React.createElement(
                  "div", { className: "sr-top" },
                  React.createElement("span", { className: "ic" }, React.createElement(Icon, { name: "fileLines" })),
                  React.createElement("span", { className: "sr-title", dangerouslySetInnerHTML: { __html: highlight(r.note.title, q) } }),
                  React.createElement("span", { className: "sr-path" }, (r.path.length ? r.path.join(" / ") : "최상위"))
                ),
                q.trim() && React.createElement("div", { className: "sr-snippet", dangerouslySetInnerHTML: { __html: snippet(r._text, q) } })
              ))
      ),
      React.createElement(
        "div", { className: "search-foot" },
        React.createElement("span", null, React.createElement("span", { className: "k" }, "↑↓"), "이동"),
        React.createElement("span", null, React.createElement("span", { className: "k" }, "↵"), "열기"),
        React.createElement("span", null, React.createElement("span", { className: "k" }, "esc"), "닫기"),
        React.createElement("span", { style: { marginLeft: "auto" } }, results.length + "개 결과")
      )
    )
  );
}
