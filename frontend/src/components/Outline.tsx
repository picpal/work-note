/* Outline — right-margin table of contents built from the note's markdown headings.
   Click a heading to scroll the editor to it; the current section is highlighted.
   Hidden via container query when the main area is too narrow (so it never covers text). */
import { useState, useEffect, useRef, useCallback } from "react";
import React from "react";
import type { EditorView } from "@codemirror/view";

function parseHeadings(src: string) {
  const lines = (src || "").split("\n");
  let inFence = false;
  const out: Array<{ level: number; text: string; line: number }> = [];
  lines.forEach((ln, i) => {
    if (/^\s*(```|~~~)/.test(ln)) { inFence = !inFence; return; }
    if (inFence) return;
    const m = ln.match(/^(#{1,6})\s+(.+?)\s*#*\s*$/);
    if (m) out.push({ level: m[1].length, text: m[2].trim(), line: i + 1 });
  });
  return out;
}

const SCROLLER = ".doc-scroll";

// environment-independent smooth scroll (some webviews ignore behavior:"smooth"
// and throttle rAF when backgrounded) — timer-based so it always runs
function smoothScrollTo(el: HTMLElement & { __wnScroll?: ReturnType<typeof setInterval> }, to: number, dur?: number) {
  dur = dur || 320;
  const start = el.scrollTop;
  const max = el.scrollHeight - el.clientHeight;
  to = Math.max(0, Math.min(to, max));
  const diff = to - start;
  if (Math.abs(diff) < 2) { el.scrollTop = to; return; }
  const ease = (x: number) => (x < 0.5 ? 2 * x * x : 1 - Math.pow(-2 * x + 2, 2) / 2);
  const t0 = (window.performance || Date).now();
  if (el.__wnScroll) clearInterval(el.__wnScroll);
  el.__wnScroll = setInterval(() => {
    const p = Math.min(1, ((window.performance || Date).now() - t0) / dur!);
    el.scrollTop = start + diff * ease(p);
    if (p >= 1) { clearInterval(el.__wnScroll); el.__wnScroll = 0 as unknown as ReturnType<typeof setInterval>; }
  }, 16);
}

interface OutlineProps {
  content: string;
  title?: string;
  viewRef: React.RefObject<EditorView | null>;
}

export function Outline({ content, title, viewRef }: OutlineProps) {
  let headings = parseHeadings(content);
  // drop the leading heading when it acts as the note's title:
  // either it matches note.title, or it is the document's very first non-empty line
  const norm = (s: string) => (s || "").replace(/\s+/g, " ").trim().toLowerCase();
  if (headings.length) {
    const lines = (content || "").split("\n");
    let firstNonEmpty = -1;
    for (let i = 0; i < lines.length; i++) { if (lines[i].trim() !== "") { firstNonEmpty = i; break; } }
    const isTitleMatch = title && norm(headings[0].text) === norm(title);
    const isLeadLine = headings[0].line - 1 === firstNonEmpty;
    if (isTitleMatch || isLeadLine) headings = headings.slice(1);
  }
  const [active, setActive] = useState(-1);
  const rafRef = useRef<ReturnType<typeof setTimeout>>(0 as unknown as ReturnType<typeof setTimeout>);

  const offsetFor = useCallback((line: number) => {
    const view = viewRef.current;
    const scroller = document.querySelector<HTMLElement>(SCROLLER);
    if (!view || !scroller) return 0;
    let block: { top: number };
    try { block = view.lineBlockAt(view.state.doc.line(line).from); } catch (e) { return 0; }
    const contentTop =
      view.contentDOM.getBoundingClientRect().top - scroller.getBoundingClientRect().top + scroller.scrollTop;
    return contentTop + block.top;
  }, [viewRef]);

  const jump = (line: number) => {
    const scroller = document.querySelector<HTMLElement>(SCROLLER);
    if (!scroller || !viewRef.current) return;
    smoothScrollTo(scroller, offsetFor(line) - 84);
    // re-measure once widgets (diagrams/tables) settle, then correct
    setTimeout(() => {
      const t = offsetFor(line) - 84;
      if (Math.abs(Math.max(0, t) - scroller.scrollTop) > 4) smoothScrollTo(scroller, t, 200);
    }, 380);
  };

  // scroll-spy: highlight the heading of the section currently at the top
  useEffect(() => {
    const scroller = document.querySelector<HTMLElement>(SCROLLER);
    if (!scroller || headings.length === 0) { setActive(-1); return; }
    let ticking = false;
    const compute = () => {
      ticking = false;
      const mark = scroller.scrollTop + 110;
      let idx = 0;
      for (let i = 0; i < headings.length; i++) {
        if (offsetFor(headings[i].line) <= mark) idx = i; else break;
      }
      // near the bottom, the last section can't reach the top mark — force it active
      if (scroller.scrollTop + scroller.clientHeight >= scroller.scrollHeight - 6) {
        idx = headings.length - 1;
      }
      setActive(idx);
    };
    const onScroll = () => {
      if (ticking) return;
      ticking = true;
      clearTimeout(rafRef.current);
      rafRef.current = setTimeout(compute, 60);
    };
    compute();
    scroller.addEventListener("scroll", onScroll, { passive: true });
    return () => { scroller.removeEventListener("scroll", onScroll); clearTimeout(rafRef.current); };
  }, [content, headings.length, offsetFor]);

  if (headings.length === 0) return null;

  const minLevel = headings.reduce((m, h) => Math.min(m, h.level), 6);

  return React.createElement(
    "nav", { className: "outline", "aria-label": "문서 개요" },
    React.createElement(
      "div", { className: "outline-list" },
      headings.map((h, i) =>
        React.createElement("button", {
          key: h.line + "-" + i,
          className: "outline-item lv" + h.level + (i === active ? " active" : ""),
          style: { paddingLeft: 10 + (h.level - minLevel) * 13 },
          title: h.text,
          onClick: () => jump(h.line),
        }, h.text))
    )
  );
}
