/* SearchModal — Cmd+K full-screen overlay. 제목 + 본문 + 태그를 검색한다.
   "#태그" 접두는 태그만 검색(에디터 태그 칩 클릭이 넣어주는 질의).
   판정·정렬은 searchMatch.ts(순수 함수)에 있고 여기서는 표시만 한다. */
import { useState, useEffect, useRef, useMemo } from "react";
import React from "react";
import { Icon } from "./Icon";
import { mdToText } from "../lib/markdown";
import { parseSearchQuery, rankMatches, FIELD_LABEL, type MatchResult, type SearchTarget } from "./searchMatch";
import { consumeSearchSeed } from "./searchBus";
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
  /** 열릴 때 채워둘 질의. 없으면 searchBus 시드(태그 칩 클릭)를 소비한다. */
  initialQuery?: string;
}

interface Entry {
  note: NoteNode;
  path: string[];
  text: string;
}

export function SearchModal({ notes, onClose, onOpen, initialQuery }: SearchModalProps) {
  // 마운트 시 1회: 명시 prop > 태그 칩 시드 > 빈 질의
  const [q, setQ] = useState(() => initialQuery ?? consumeSearchSeed());
  const [sel, setSel] = useState(0);
  const inputRef = useRef<HTMLInputElement>(null);
  const listRef = useRef<HTMLDivElement>(null);

  useEffect(() => { inputRef.current && inputRef.current.focus(); }, []);

  const idx = useMemo<Entry[]>(
    () => notes.map((n) => ({ note: n.note, path: n.path, text: mdToText(n.note.content) })),
    [notes],
  );

  const parsed = parseSearchQuery(q);

  const results = useMemo<Array<Entry & { match: MatchResult | null }>>(() => {
    if (!parsed.term) return idx.slice(0, 30).map((e) => ({ ...e, match: null }));
    const target = (e: Entry): SearchTarget => ({ title: e.note.title, text: e.text, tags: e.note.tags || [] });
    return rankMatches(idx, target, q).map((r) => ({ ...r.item, match: r.match }));
  }, [q, idx, parsed.term]);

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

  // 하이라이트 대상 원문(태그 전용 질의는 "#"을 뗀 태그명)
  const hl = parsed.term ? q.trim().replace(/^#\s*/, "") : "";

  const resultRow = (r: Entry & { match: MatchResult | null }, i: number) =>
    React.createElement(
      "div", {
        key: r.note.id + "-" + i, className: "sr-item" + (i === sel ? " sel" : ""),
        onMouseEnter: () => setSel(i), onClick: () => choose(r),
      },
      React.createElement(
        "div", { className: "sr-top" },
        React.createElement("span", { className: "ic" }, React.createElement(Icon, { name: "fileLines" })),
        React.createElement("span", { className: "sr-title", dangerouslySetInnerHTML: { __html: highlight(r.note.title, r.match?.fields.includes("title") ? hl : "") } }),
        // 왜 매칭됐는지 — 제목/태그/본문 배지
        (r.match?.fields || []).map((f) => React.createElement("span", { key: f, className: "sr-why" }, FIELD_LABEL[f])),
        React.createElement("span", { className: "sr-path" }, (r.path.length ? r.path.join(" / ") : "최상위"))
      ),
      r.match && r.match.tags.length > 0 && React.createElement(
        "div", { className: "sr-tags" },
        r.match.tags.map((t) =>
          React.createElement("span", { key: t, className: "sr-tag", dangerouslySetInnerHTML: { __html: "#" + highlight(t, hl) } }))
      ),
      r.match?.fields.includes("body") && React.createElement("div", { className: "sr-snippet", dangerouslySetInnerHTML: { __html: snippet(r.text, hl) } })
    );

  return React.createElement(
    "div", { className: "search-overlay", onMouseDown: onClose },
    React.createElement(
      "div", { className: "search-box", onMouseDown: (e: React.MouseEvent) => e.stopPropagation() },
      React.createElement(
        "div", { className: "search-head" },
        React.createElement(Icon, { name: "search" }),
        React.createElement("input", {
          ref: inputRef, value: q, placeholder: "제목·내용·태그 검색…",
          onChange: (e: React.ChangeEvent<HTMLInputElement>) => setQ(e.target.value), onKeyDown: onKey,
        }),
        React.createElement("span", { className: "esc" }, "ESC")
      ),
      React.createElement(
        "div", { className: "search-results", ref: listRef },
        results.length === 0
          ? React.createElement("div", { className: "search-empty" },
              parsed.tagOnly
                ? "“#" + hl + "” 태그가 붙은 노트가 없습니다"
                : "“" + q + "” 와 일치하는 노트가 없습니다")
          : results.map(resultRow)
      ),
      React.createElement(
        "div", { className: "search-foot" },
        React.createElement("span", null, React.createElement("span", { className: "k" }, "↑↓"), "이동"),
        React.createElement("span", null, React.createElement("span", { className: "k" }, "↵"), "열기"),
        React.createElement("span", null, React.createElement("span", { className: "k" }, "#"), "태그만"),
        React.createElement("span", null, React.createElement("span", { className: "k" }, "esc"), "닫기"),
        React.createElement("span", { style: { marginLeft: "auto" } }, results.length + "개 결과")
      )
    )
  );
}
