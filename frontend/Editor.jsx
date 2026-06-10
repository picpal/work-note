/* Editor — title + tags + a single CodeMirror 6 live-preview surface (Obsidian-style).
   Type like a notepad; markdown renders inline and markers reveal only on the cursor line.
   Falls back to a plain textarea if the CM module fails to load. */
(function () {
  const { useState, useRef, useEffect, useLayoutEffect } = React;

  const TEMPLATES = {
    table: "| 항목 | 설명 |\n| --- | --- |\n| 내용 | 내용 |\n| 내용 | 내용 |",
    code: "```js\nfunction hello() {\n  return \"world\";\n}\n```",
    mermaid: "```mermaid\nflowchart TD\n  A[시작] --> B{조건}\n  B -->|예| C[처리]\n  B -->|아니오| D[종료]\n```",
    sequence: "```mermaid\nsequenceDiagram\n  participant A as 클라이언트\n  participant B as 서버\n  A->>B: 요청\n  B-->>A: 응답\n```",
    image: "![이미지 설명](image.png)",
  };

  function grow(el) { if (!el) return; el.style.height = "auto"; el.style.height = el.scrollHeight + "px"; }

  function Editor(props) {
    const { note, theme, onChange, registerToolbar } = props;
    const hostRef = useRef(null);       // CM mount point
    const viewRef = useRef(null);       // CM EditorView
    const fbRef = useRef(null);         // fallback textarea
    const onChangeRef = useRef(onChange);
    const titleRef = useRef(null);
    const [fallback, setFallback] = useState(false);
    onChangeRef.current = onChange;

    useLayoutEffect(() => { grow(titleRef.current); }, []);

    // ---- mount CodeMirror ----
    useEffect(() => {
      let timer;
      const mount = () => {
        if (!hostRef.current || viewRef.current || !window.WN_CM) return;
        viewRef.current = window.WN_CM.create(hostRef.current, {
          doc: note.content || "",
          onChange: (text) => onChangeRef.current({ content: text }),
        });
        props.onView && props.onView(viewRef.current);
      };
      if (window.WN_CM) mount();
      else {
        const onReady = () => mount();
        window.addEventListener("wn-cm-ready", onReady);
        timer = setTimeout(() => { if (!viewRef.current) setFallback(true); }, 4500);
        return () => {
          window.removeEventListener("wn-cm-ready", onReady);
          clearTimeout(timer);
          if (viewRef.current) { props.onView && props.onView(null); viewRef.current.destroy(); viewRef.current = null; }
        };
      }
      return () => { if (viewRef.current) { props.onView && props.onView(null); viewRef.current.destroy(); viewRef.current = null; } };
    }, []);

    // ---- recolor mermaid widgets when theme flips ----
    useEffect(() => { if (window.setMermaidTheme) window.setMermaidTheme(theme === "dark"); }, [theme]);

    // ---- toolbar wiring (works on CM view, or fallback textarea) ----
    const fbWrap = (left, right, ph) => {
      const el = fbRef.current; if (!el) return;
      const s = el.selectionStart, e = el.selectionEnd, v = el.value;
      const sel = v.slice(s, e) || ph || "";
      el.value = v.slice(0, s) + left + sel + right + v.slice(e);
      onChangeRef.current({ content: el.value });
      el.focus(); el.setSelectionRange(s + left.length, s + left.length + sel.length);
    };
    const fbBlock = (text) => {
      const el = fbRef.current; if (!el) return;
      const s = el.selectionStart, v = el.value;
      const ins = (s === 0 || v[s - 1] === "\n" ? "" : "\n\n") + text + "\n";
      el.value = v.slice(0, s) + ins + v.slice(s);
      onChangeRef.current({ content: el.value });
      el.focus(); el.setSelectionRange(s + ins.length, s + ins.length);
    };
    const act = (cmFn, fbFn) => () => {
      if (viewRef.current) cmFn(viewRef.current);
      else fbFn && fbFn();
    };

    useEffect(() => {
      const CM = window.WN_CM;
      registerToolbar({
        h: (n) => act((v) => CM.heading(v, n), () => fbBlock("#".repeat(n) + " 제목"))(),
        bold: act((v) => CM.wrap(v, "**", "**", "굵게"), () => fbWrap("**", "**", "굵게")),
        italic: act((v) => CM.wrap(v, "_", "_", "기울임"), () => fbWrap("_", "_", "기울임")),
        strike: act((v) => CM.wrap(v, "~~", "~~", "취소선"), () => fbWrap("~~", "~~", "취소선")),
        quote: act((v) => CM.prefix(v, "> "), () => fbBlock("> 인용문")),
        list: act((v) => CM.prefix(v, "- "), () => fbBlock("- 항목")),
        checklist: act((v) => CM.prefix(v, "- [ ] "), () => fbBlock("- [ ] 할 일")),
        link: act((v) => CM.wrap(v, "[", "](https://)", "링크"), () => fbWrap("[", "](https://)", "링크")),
        image: act((v) => CM.block(v, TEMPLATES.image), () => fbBlock(TEMPLATES.image)),
        code: act((v) => CM.block(v, TEMPLATES.code), () => fbBlock(TEMPLATES.code)),
        table: act((v) => CM.block(v, TEMPLATES.table), () => fbBlock(TEMPLATES.table)),
        mermaid: act((v) => CM.block(v, TEMPLATES.mermaid), () => fbBlock(TEMPLATES.mermaid)),
        sequence: act((v) => CM.block(v, TEMPLATES.sequence), () => fbBlock(TEMPLATES.sequence)),
      });
    });

    // ---- tags ----
    const [tagDraft, setTagDraft] = useState("");
    const addTag = (v) => {
      v = v.trim().replace(/^#/, "");
      if (!v) return;
      if (!(note.tags || []).includes(v)) onChange({ tags: [...(note.tags || []), v] });
      setTagDraft("");
    };
    const removeTag = (t) => onChange({ tags: (note.tags || []).filter((x) => x !== t) });

    return React.createElement(
      "div", { className: "doc", key: note.id },
      React.createElement("textarea", {
        className: "title-input", ref: titleRef, rows: 1, placeholder: "제목을 입력하세요",
        value: note.title,
        onChange: (e) => { onChange({ title: e.target.value }); grow(e.target); },
        onKeyDown: (e) => { if (e.key === "Enter") { e.preventDefault(); viewRef.current && viewRef.current.focus(); } },
      }),
      React.createElement("div", { className: "title-rule" }),
      React.createElement(
        "div", { className: "tags-row" },
        (note.tags || []).map((t) =>
          React.createElement("span", { className: "tag", key: t }, "#" + t,
            React.createElement("button", { onClick: () => removeTag(t), title: "삭제" }, "×"))),
        React.createElement("input", {
          className: "tag-input", placeholder: (note.tags || []).length ? "태그 추가" : "태그를 입력하세요",
          value: tagDraft,
          onChange: (e) => setTagDraft(e.target.value),
          onKeyDown: (e) => {
            if (e.key === "Enter" || e.key === "," || (e.key === "Tab" && tagDraft.trim() !== "")) {
              e.preventDefault();
              addTag(tagDraft);
              e.target.focus();
            }
            if (e.key === "Backspace" && tagDraft === "" && (note.tags || []).length) removeTag(note.tags[note.tags.length - 1]);
          },
          onBlur: () => addTag(tagDraft),
        })
      ),
      // editor surface
      fallback
        ? React.createElement("textarea", {
            className: "cm-fallback", ref: fbRef, defaultValue: note.content || "",
            placeholder: "내용을 입력하세요…",
            onChange: (e) => onChange({ content: e.target.value }),
          })
        : React.createElement(
            React.Fragment, null,
            React.createElement("div", { className: "cm-host", ref: hostRef }),
            React.createElement("div", {
              className: "cm-tail",
              onMouseDown: (e) => {
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

  window.Editor = Editor;
})();
