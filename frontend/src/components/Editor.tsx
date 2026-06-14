/* Editor — title + tags + a single CodeMirror 6 live-preview surface (Obsidian-style).
   Type like a notepad; markdown renders inline and markers reveal only on the cursor line.
   CDN fallback path removed — cm module is always available in the bundle. */
import { createElement, Fragment, useState, useRef, useEffect, useLayoutEffect } from "react";
import type { NoteNode } from "../types";
import type { EditorView } from "@codemirror/view";
import * as cm from "../editor/cm";
import { setMermaidTheme } from "../lib/markdown";
import { AttachmentApi } from "../storage/AttachmentApi";
import { AttachmentBar } from "./AttachmentBar";
import { ApiError } from "../api/http";

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
  toast: (msg: string, icon?: string) => void;
  canUpload: boolean;
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
  const fileRef = useRef<HTMLInputElement>(null);
  const onChangeRef = useRef(onChange);
  const titleRef = useRef<HTMLTextAreaElement>(null);
  onChangeRef.current = onChange;
  // 첨부영역 새로고침 트리거 — 업로드/삭제 후 bump (Editor는 note별 리마운트라 노트 전환 시 0으로 초기화).
  const [attachVersion, setAttachVersion] = useState(0);

  // ---- 첨부 업로드 (stale closure 방지: 항상 최신 note/props를 ref로 참조) ----
  const uploadDepsRef = useRef({ note, toast: props.toast, canUpload: props.canUpload });
  uploadDepsRef.current = { note, toast: props.toast, canUpload: props.canUpload };
  // at: 드롭 위치(문서 offset). 지정 시 그 위치에 이미지 삽입, 없으면 커서(파일피커·붙여넣기).
  const uploadFiles = async (files: FileList | File[], at?: number) => {
    const { note: cur, toast, canUpload } = uploadDepsRef.current;
    if (!canUpload) { toast("서버 모드에서만 첨부할 수 있습니다"); return; }
    const v = viewRef.current;
    if (!v) return;
    let added = 0;
    let pos = at; // 여러 이미지 연속 삽입 시 길이만큼 전진
    for (const file of Array.from(files)) {
      toast("업로드 중…");
      try {
        const res = await AttachmentApi.upload(cur.id, file);
        // 이미지는 본문에 인라인 미리보기 마크다운 삽입. 비이미지는 본문 표기 없이 첨부영역에만 등록.
        const isImg = /\.(png|jpe?g|gif|webp)$/i.test(res.filename);
        if (isImg) {
          const md = `![${res.filename}](${res.url})\n`;
          if (pos != null) { cm.insertAt(v, pos, md); pos += md.length; }
          else cm.insertAtCursor(v, md);
        }
        added++;
        toast("첨부했습니다", "check");
      } catch (e) {
        toast(e instanceof ApiError ? e.message : "업로드 실패");
      }
    }
    if (added) setAttachVersion((n) => n + 1); // 첨부영역 새로고침
  };
  const uploadRef = useRef(uploadFiles);
  uploadRef.current = uploadFiles;

  useLayoutEffect(() => { grow(titleRef.current); }, []);

  // ---- mount CodeMirror ----
  useEffect(() => {
    if (!hostRef.current) return;
    viewRef.current = cm.create(hostRef.current, {
      doc: note.content || "",
      onChange: (text) => onChangeRef.current({ content: text }),
    });
    props.onView && props.onView(viewRef.current);
    // drop/paste 첨부 — CM DOM에 직접 바인딩(ref 경유 최신 uploadFiles 호출로 stale closure 회피)
    const dom = viewRef.current.dom;
    const onDrop = (e: DragEvent) => {
      if (!e.dataTransfer?.files?.length) return;
      e.preventDefault();
      // 드롭 좌표 → 문서 위치. 그래야 이미지가 직전 커서가 아닌 "떨어뜨린 그 자리"에 삽입된다.
      const v = viewRef.current;
      const at = v ? cm.posAtCoords(v, e.clientX, e.clientY) : null;
      void uploadRef.current(e.dataTransfer.files, at ?? undefined);
    };
    const onPaste = (e: ClipboardEvent) => {
      const files = e.clipboardData?.files;
      if (files && files.length) { e.preventDefault(); void uploadRef.current(files); }
    };
    dom.addEventListener("drop", onDrop);
    dom.addEventListener("paste", onPaste);
    return () => {
      dom.removeEventListener("drop", onDrop);
      dom.removeEventListener("paste", onPaste);
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
      image: () => {
        // 서버 모드면 파일 피커를 열어 실제 첨부 업로드, 아니면 기존 마크다운 템플릿 삽입
        if (props.canUpload) fileRef.current?.click();
        else if (viewRef.current) cm.block(viewRef.current, TEMPLATES.image);
      },
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
    // 첨부파일 영역 (http 모드에서만 — local 모드는 백엔드 없음)
    props.canUpload
      ? createElement(AttachmentBar, {
          load: () => AttachmentApi.list(note.id),
          reloadKey: attachVersion,
          removable: true,
          onRemove: (id: string) => AttachmentApi.remove(id),
          toast: props.toast,
        })
      : null,
    // editor surface
    createElement(
      Fragment, null,
      createElement("input", {
        type: "file", ref: fileRef, multiple: true, style: { display: "none" },
        onChange: (e: React.ChangeEvent<HTMLInputElement>) => {
          if (e.target.files?.length) void uploadFiles(e.target.files);
          e.target.value = "";
        },
      }),
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
