/* Editor — title + tags + a single CodeMirror 6 live-preview surface (Obsidian-style).
   Type like a notepad; markdown renders inline and markers reveal only on the cursor line.
   CDN fallback path removed — cm module is always available in the bundle. */
import { createElement, Fragment, useState, useRef, useEffect, useLayoutEffect } from "react";
import type { NoteNode } from "../types";
import type { EditorView } from "@codemirror/view";
import * as cm from "../editor/cm";
import { setMermaidTheme } from "../lib/markdown";

export interface ToolbarHandlers {
  h: (n: number) => void;
  bold: () => void;
  italic: () => void;
  strike: () => void;
  quote: () => void;
  list: () => void;
  checklist: () => void;
  link: () => void;
  image: () => void;
  code: () => void;
  table: () => void;
  mermaid: () => void;
  sequence: () => void;
}

interface EditorProps {
  note: NoteNode;
  theme: "dark" | "light";
  onChange: (patch: Partial<NoteNode>) => void;
  registerToolbar: (handlers: ToolbarHandlers) => void;
  onView?: (v: EditorView | null) => void;
}

const TEMPLATES = {
  table: "| 항목 | 설명 |\n| --- | --- |\n| 내용 | 내용 |\n| 내용 | 내용 |",
  code: "```js\nfunction hello() {\n  return \"world\";\n}\n```",
  mermaid: "```mermaid\nflowchart TD\n  A[시작] --> B{조건}\n  B -->|예| C[처리]\n  B -->|아니오| D[종료]\n```",
  sequence: "```mermaid\nsequenceDiagram\n  participant A as 클라이언트\n  participant B as 서버\n  A->>B: 요청\n  B-->>A: 응답\n```",
  image: "![이미지 설명](image.png)",
};

function grow(el: HTMLTextAreaElement | null) {
  if (!el) return;
  el.style.height = "auto";
  el.style.height = el.scrollHeight + "px";
}

export function Editor(props: EditorProps) {
  const { note, theme, onChange, registerToolbar } = props;
  const hostRef = useRef<HTMLDivElement>(null);
  const viewRef = useRef<EditorView | null>(null);
  const onChangeRef = useRef(onChange);
  const titleRef = useRef<HTMLTextAreaElement>(null);
  onChangeRef.current = onChange;

  useLayoutEffect(() => { grow(titleRef.current); }, []);

  // ---- mount CodeMirror ----
  useEffect(() => {
    if (!hostRef.current) return;
    viewRef.current = cm.create(hostRef.current, {
      doc: note.content || "",
      onChange: (text) => onChangeRef.current({ content: text }),
    });
    props.onView && props.onView(viewRef.current);
    return () => {
      if (viewRef.current) {
        props.onView && props.onView(null);
        viewRef.current.destroy();
        viewRef.current = null;
      }
    };
  }, []);

  // ---- recolor mermaid widgets when theme flips ----
  useEffect(() => { setMermaidTheme(theme === "dark"); }, [theme]);

  // ---- toolbar wiring ----
  useEffect(() => {
    registerToolbar({
      h: (n) => { if (viewRef.current) cm.heading(viewRef.current, n); },
      bold: () => { if (viewRef.current) cm.wrap(viewRef.current, "**", "**", "굵게"); },
      italic: () => { if (viewRef.current) cm.wrap(viewRef.current, "_", "_", "기울임"); },
      strike: () => { if (viewRef.current) cm.wrap(viewRef.current, "~~", "~~", "취소선"); },
      quote: () => { if (viewRef.current) cm.prefix(viewRef.current, "> "); },
      list: () => { if (viewRef.current) cm.prefix(viewRef.current, "- "); },
      checklist: () => { if (viewRef.current) cm.prefix(viewRef.current, "- [ ] "); },
      link: () => { if (viewRef.current) cm.wrap(viewRef.current, "[", "](https://)", "링크"); },
      image: () => { if (viewRef.current) cm.block(viewRef.current, TEMPLATES.image); },
      code: () => { if (viewRef.current) cm.block(viewRef.current, TEMPLATES.code); },
      table: () => { if (viewRef.current) cm.block(viewRef.current, TEMPLATES.table); },
      mermaid: () => { if (viewRef.current) cm.block(viewRef.current, TEMPLATES.mermaid); },
      sequence: () => { if (viewRef.current) cm.block(viewRef.current, TEMPLATES.sequence); },
    });
  });

  // ---- tags ----
  const [tagDraft, setTagDraft] = useState("");
  const addTag = (v: string) => {
    v = v.trim().replace(/^#/, "");
    if (!v) return;
    if (!(note.tags || []).includes(v)) onChange({ tags: [...(note.tags || []), v] });
    setTagDraft("");
  };
  const removeTag = (t: string) => onChange({ tags: (note.tags || []).filter((x) => x !== t) });

  return createElement(
    "div", { className: "doc", key: note.id },
    createElement("textarea", {
      className: "title-input", ref: titleRef, rows: 1, placeholder: "제목을 입력하세요",
      value: note.title,
      onChange: (e: React.ChangeEvent<HTMLTextAreaElement>) => { onChange({ title: e.target.value }); grow(e.target); },
      onKeyDown: (e: React.KeyboardEvent<HTMLTextAreaElement>) => { if (e.key === "Enter") { e.preventDefault(); viewRef.current && viewRef.current.focus(); } },
    }),
    createElement("div", { className: "title-rule" }),
    createElement(
      "div", { className: "tags-row" },
      (note.tags || []).map((t) =>
        createElement("span", { className: "tag", key: t }, "#" + t,
          createElement("button", { onClick: () => removeTag(t), title: "삭제" }, "×"))),
      createElement("input", {
        className: "tag-input", placeholder: (note.tags || []).length ? "태그 추가" : "태그를 입력하세요",
        value: tagDraft,
        onChange: (e: React.ChangeEvent<HTMLInputElement>) => setTagDraft(e.target.value),
        onKeyDown: (e: React.KeyboardEvent<HTMLInputElement>) => {
          if (e.key === "Enter" || e.key === "," || (e.key === "Tab" && tagDraft.trim() !== "")) {
            e.preventDefault();
            addTag(tagDraft);
            (e.target as HTMLInputElement).focus();
          }
          if (e.key === "Backspace" && tagDraft === "" && (note.tags || []).length) removeTag(note.tags[note.tags.length - 1]);
        },
        onBlur: () => addTag(tagDraft),
      })
    ),
    // editor surface
    createElement(
      Fragment, null,
      createElement("div", { className: "cm-host", ref: hostRef }),
      createElement("div", {
        className: "cm-tail",
        onMouseDown: (e: React.MouseEvent<HTMLDivElement>) => {
          e.preventDefault();
          const v = viewRef.current;
          if (!v) return;
          v.focus();
          const end = v.state.doc.length;
          v.dispatch({ selection: { anchor: end }, scrollIntoView: true });
        },
      })
    )
  );
}
