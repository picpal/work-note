// cm.js — CodeMirror 6 "Live Preview" markdown editor (Obsidian-style).
// One continuous editable surface. Markdown markers (#, **, `, etc.) are hidden
// except on the line the cursor is on; everything else shows formatted. Checkboxes,
// horizontal rules, mermaid diagrams and tables render as widgets when the cursor
// is outside them. Exposed via window.WN_CM.
import { EditorState, Compartment, StateField, StateEffect } from "@codemirror/state";
import { EditorView, Decoration, ViewPlugin, WidgetType, keymap, placeholder as cmPlaceholder } from "@codemirror/view";
import { syntaxTree, StreamLanguage, HighlightStyle, syntaxHighlighting } from "@codemirror/language";
import { markdown, markdownLanguage } from "@codemirror/lang-markdown";
import { GFM } from "@lezer/markdown";
import { history, historyKeymap, defaultKeymap, indentMore, indentLess } from "@codemirror/commands";
import { autocompletion, completionKeymap } from "@codemirror/autocomplete";
import { tags as t } from "@lezer/highlight";
// language packages (for nested highlighting inside fenced code blocks)
import { sql } from "@codemirror/lang-sql";
import { javascript } from "@codemirror/lang-javascript";
import { java } from "@codemirror/lang-java";
import { shell } from "@codemirror/legacy-modes/mode/shell";

// nested code languages used by markdown fenced blocks
const sqlLang = sql().language;
const jsLang = javascript().language;
const javaLang = java().language;
const shellLang = StreamLanguage.define(shell);

function codeLanguageFor(info) {
  const l = (info || "").toLowerCase().trim();
  if (l === "sql" || l === "mysql" || l === "postgres" || l === "postgresql" || l === "plsql") return sqlLang;
  if (["js", "javascript", "jsx", "node", "mjs", "cjs"].includes(l)) return jsLang;
  if (l === "java") return javaLang;
  if (["bash", "sh", "shell", "zsh", "console", "shellscript"].includes(l)) return shellLang;
  return null;
}

// monotone-muted syntax highlight style (only touches code-relevant tags, not markdown body)
const codeHighlight = HighlightStyle.define([
  { tag: [t.keyword, t.controlKeyword, t.moduleKeyword, t.operatorKeyword, t.definitionKeyword], color: "var(--sx-key)", fontWeight: "600" },
  { tag: [t.string, t.special(t.string), t.regexp, t.inserted], color: "var(--sx-str)" },
  { tag: [t.number, t.bool, t.atom, t.literal], color: "var(--sx-num)" },
  { tag: [t.comment, t.lineComment, t.blockComment, t.meta, t.docComment], color: "var(--sx-comment)", fontStyle: "italic" },
  { tag: [t.function(t.variableName), t.function(t.propertyName), t.labelName], color: "var(--sx-fn)" },
  { tag: [t.typeName, t.className, t.namespace, t.annotation, t.modifier, t.self], color: "var(--sx-type)", fontWeight: "600" },
  { tag: [t.propertyName, t.attributeName], color: "var(--sx-attr)" },
  { tag: [t.operator, t.punctuation, t.separator, t.derefOperator], color: "var(--text-2)" },
  { tag: [t.variableName, t.name, t.character, t.macroName], color: "var(--text)" },
  { tag: t.invalid, color: "var(--text-3)" },
]);

// ---------- keyword autocomplete (offline, per-fence-language) ----------
const KEYWORDS = {
  sql: { type: "keyword", words: "SELECT FROM WHERE INSERT INTO VALUES UPDATE SET DELETE CREATE TABLE ALTER DROP ADD COLUMN PRIMARY KEY FOREIGN REFERENCES INDEX VIEW JOIN INNER LEFT RIGHT OUTER FULL CROSS ON AS DISTINCT GROUP BY ORDER HAVING LIMIT OFFSET UNION ALL AND OR NOT NULL IS LIKE IN BETWEEN EXISTS CASE WHEN THEN ELSE END ASC DESC COUNT SUM AVG MIN MAX COALESCE CAST WITH TRUNCATE BEGIN COMMIT ROLLBACK TRANSACTION".split(" ") },
  js: { type: "keyword", words: "const let var function return if else for while do switch case break continue default new class extends super this typeof instanceof in of try catch finally throw async await yield import export from as null undefined true false void delete static get set Promise Array Object String Number Boolean Math JSON Map Set console document window".split(" ") },
  java: { type: "keyword", words: "public private protected static final abstract class interface extends implements enum void int long short byte float double boolean char String var new return if else for while do switch case break continue default try catch finally throw throws this super import package synchronized volatile transient native instanceof null true false System out println String List Map ArrayList HashMap Integer Object Override".split(" ") },
  bash: { type: "keyword", words: "if then elif else fi for while do done case esac function return local export readonly declare echo printf read cd ls pwd cat grep sed awk find xargs cut sort uniq head tail wc tr chmod chmod chown mkdir rm cp mv touch curl wget tar gzip ssh scp sudo exit set unset source alias test".split(" ") },
};
function normalizeLang(l) {
  l = (l || "").toLowerCase().trim();
  if (["js", "javascript", "jsx", "node", "mjs", "cjs"].includes(l)) return "js";
  if (l === "java") return "java";
  if (["bash", "sh", "shell", "zsh", "console", "shellscript"].includes(l)) return "bash";
  if (["sql", "mysql", "postgres", "postgresql", "plsql"].includes(l)) return "sql";
  return null;
}
// find the language of the fenced block the cursor is inside (text scan, robust vs mounts)
function currentFenceLang(state, pos) {
  const curLine = state.doc.lineAt(pos).number;
  for (let n = curLine; n >= 1; n--) {
    const text = state.doc.line(n).text;
    const m = text.match(/^\s*(```|~~~)\s*([\w+#-]*)\s*$/);
    if (m) {
      if (n === curLine) return null;          // on a fence line itself
      return m[2] ? m[2] : null;               // opener carries the language; bare fence = closer → outside
    }
  }
  return null;
}
// true when the caret is inside a fenced code block body (between opener and closer)
function inCodeBlock(state, pos) {
  const curLine = state.doc.lineAt(pos);
  if (/^\s*(```|~~~)/.test(curLine.text)) return false; // on a fence line itself
  let count = 0;
  for (let n = 1; n < curLine.number; n++) {
    if (/^\s*(```|~~~)/.test(state.doc.line(n).text)) count++;
  }
  return count % 2 === 1;
}
// Tab inside a code block inserts indentation instead of moving focus out
function tabInCode(view) {
  if (!inCodeBlock(view.state, view.state.selection.main.head)) return false;
  const sel = view.state.selection.main;
  if (sel.empty) {
    view.dispatch(view.state.update(view.state.replaceSelection("  "), { scrollIntoView: true, userEvent: "input.indent" }));
    return true;
  }
  return indentMore(view);
}
function shiftTabInCode(view) {
  if (!inCodeBlock(view.state, view.state.selection.main.head)) return false;
  return indentLess(view);
}
function fenceCompletion(ctx) {
  const lang = normalizeLang(currentFenceLang(ctx.state, ctx.pos));
  if (!lang) return null;
  const set = KEYWORDS[lang];
  const word = ctx.matchBefore(/[\w$]+/);
  if (!word || (word.from === word.to && !ctx.explicit)) return null;
  return {
    from: word.from,
    options: set.words.map((w) => ({ label: w, type: set.type })),
    validFor: /^[\w$]*$/,
  };
}

// ---------- widgets ----------
class CheckboxWidget extends WidgetType {
  constructor(checked, from, to) { super(); this.checked = checked; this.from = from; this.to = to; }
  eq(o) { return o.checked === this.checked && o.from === this.from; }
  toDOM(view) {
    const span = document.createElement("span");
    span.className = "cm-md-checkbox" + (this.checked ? " done" : "");
    span.setAttribute("aria-hidden", "true");
    if (this.checked) span.innerHTML = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="3.4" stroke-linecap="round" stroke-linejoin="round"><polyline points="20 6 9 17 4 12"/></svg>';
    span.addEventListener("mousedown", (e) => {
      e.preventDefault();
      view.dispatch({ changes: { from: this.from, to: this.to, insert: this.checked ? "[ ]" : "[x]" } });
    });
    return span;
  }
  ignoreEvent() { return false; }
}

class BulletWidget extends WidgetType {
  eq() { return true; }
  toDOM() { const s = document.createElement("span"); s.className = "cm-md-bullet"; s.textContent = "•"; return s; }
}

class HrWidget extends WidgetType {
  eq() { return true; }
  toDOM() { const d = document.createElement("div"); d.className = "cm-md-hr"; d.innerHTML = "<hr/>"; return d; }
  ignoreEvent() { return false; }
}

class RenderWidget extends WidgetType {
  // generic "click to edit" rendered block (mermaid / table)
  constructor(kind, source, pos) { super(); this.kind = kind; this.source = source; this.pos = pos; }
  eq(o) { return o.kind === this.kind && o.source === this.source; }
  toDOM(view) {
    const wrap = document.createElement("div");
    wrap.className = "cm-md-render cm-md-" + this.kind;
    wrap.addEventListener("mousedown", (e) => {
      // place caret into the block so it reveals raw markdown for editing
      e.preventDefault();
      view.dispatch({ selection: { anchor: this.pos } });
      view.focus();
    });
    if (this.kind === "mermaid") {
      const m = document.createElement("div");
      m.className = "mermaid-wrap";
      m.setAttribute("data-src", encodeURIComponent(this.source));
      wrap.appendChild(m);
      requestAnimationFrame(() => { if (window.enhanceMermaid) window.enhanceMermaid(wrap); });
    } else {
      const inner = document.createElement("div");
      inner.className = "md";
      inner.innerHTML = window.renderMarkdown ? window.renderMarkdown(this.source) : this.source;
      wrap.appendChild(inner);
    }
    return wrap;
  }
  ignoreEvent() { return false; }
  get estimatedHeight() { return this.kind === "mermaid" ? 180 : 80; }
}

// ---------- focus state (unfocused editor = fully rendered clean preview) ----------
const setFocus = StateEffect.define();
const focusField = StateField.define({
  create: () => false,
  update: (v, tr) => { for (const e of tr.effects) if (e.is(setFocus)) return e.value; return v; },
});

// ---------- live preview decorations ----------
function buildDecorations(state) {
  const focused = state.field(focusField, false);
  const sel = state.selection.main;
  const aFrom = state.doc.lineAt(sel.from).number;
  const aTo = state.doc.lineAt(sel.to).number;
  // when the editor isn't focused, no line is "active" → markers stay hidden and
  // code/tables/mermaid all render (matches Obsidian's unfocused reading view)
  const lineActive = (pos) => { if (!focused) return false; const n = state.doc.lineAt(pos).number; return n >= aFrom && n <= aTo; };
  const selTouches = (from, to) => focused && sel.from <= to && sel.to >= from;
  const out = [];

  const tree = syntaxTree(state);
  tree.iterate({
      enter: (node) => {
        const name = node.name;
        const nf = node.from, nt = node.to;

        // ---- block widgets (mermaid / table / hr) ----
        if (name === "FencedCode") {
          // an unclosed fence (no second ```), should NOT style the rest of the doc
          let markCount = 0;
          for (let c = node.node.firstChild; c; c = c.nextSibling) if (c.name === "CodeMark") markCount++;
          const closed = markCount >= 2;
          if (!closed && !selTouches(nf, nt)) return false; // leave as plain text until closed
          const info = node.node.getChild("CodeInfo");
          const lang = info ? state.sliceDoc(info.from, info.to).trim().toLowerCase() : "";
          const firstLine = state.doc.lineAt(nf), lastLine = state.doc.lineAt(Math.min(nt, state.doc.length));
          if (lang === "mermaid" && !selTouches(nf, nt)) {
            const codeNode = node.node.getChild("CodeText");
            const src = codeNode ? state.sliceDoc(codeNode.from, codeNode.to) : "";
            out.push(Decoration.replace({ widget: new RenderWidget("mermaid", src, nf), block: true }).range(firstLine.from, lastLine.to));
          } else if (closed && !selTouches(nf, nt)) {
            // render a highlighted code-block card when cursor is outside
            const src = state.sliceDoc(firstLine.from, lastLine.to);
            out.push(Decoration.replace({ widget: new RenderWidget("codecard", src, firstLine.from + 3), block: true }).range(firstLine.from, lastLine.to));
          } else {
            for (let l = firstLine.number; l <= lastLine.number; l++) {
              const ln = state.doc.line(l);
              out.push(Decoration.line({ class: "cm-md-codeblock" + (l === firstLine.number ? " first" : "") + (l === lastLine.number ? " last" : "") }).range(ln.from));
            }
          }
          return false;
        }
        if (name === "Table") {
          if (!selTouches(nf, nt)) {
            const firstLine = state.doc.lineAt(nf), lastLine = state.doc.lineAt(Math.min(nt, state.doc.length));
            const src = state.sliceDoc(firstLine.from, lastLine.to);
            out.push(Decoration.replace({ widget: new RenderWidget("table", src, nf), block: true }).range(firstLine.from, lastLine.to));
            return false;
          }
          return; // editing: show raw
        }
        if (name === "HorizontalRule") {
          if (!lineActive(nf)) {
            const ln = state.doc.lineAt(nf);
            out.push(Decoration.replace({ widget: new HrWidget(), block: true }).range(ln.from, ln.to));
          }
          return false;
        }

        // ---- headings ----
        let hm = name.match(/^ATXHeading(\d)/);
        if (hm) {
          const ln = state.doc.lineAt(nf);
          out.push(Decoration.line({ class: "cm-md-h" + hm[1] }).range(ln.from));
          return;
        }
        if (name === "Blockquote") {
          const firstLine = state.doc.lineAt(nf), lastLine = state.doc.lineAt(Math.min(nt, state.doc.length));
          for (let l = firstLine.number; l <= lastLine.number; l++) out.push(Decoration.line({ class: "cm-md-quote" }).range(state.doc.line(l).from));
          return;
        }

        // ---- inline styling ----
        if (name === "StrongEmphasis") out.push(Decoration.mark({ class: "cm-md-strong" }).range(nf, nt));
        else if (name === "Emphasis") out.push(Decoration.mark({ class: "cm-md-em" }).range(nf, nt));
        else if (name === "Strikethrough") out.push(Decoration.mark({ class: "cm-md-strike" }).range(nf, nt));
        else if (name === "InlineCode") out.push(Decoration.mark({ class: "cm-md-code" }).range(nf, nt));
        else if (name === "Link") out.push(Decoration.mark({ class: "cm-md-link" }).range(nf, nt));

        // ---- task checkbox ----
        if (name === "TaskMarker") {
          const txt = state.sliceDoc(nf, nt);
          const checked = /x/i.test(txt);
          out.push(Decoration.replace({ widget: new CheckboxWidget(checked, nf, nt) }).range(nf, nt));
          return;
        }

        // ---- hide markers off the active line ----
        const isMark =
          name === "HeaderMark" || name === "EmphasisMark" || name === "StrikethroughMark" ||
          name === "CodeMark" || name === "QuoteMark" || name === "LinkMark" || name === "URL";
        if (isMark && !lineActive(nf)) {
          // for HeaderMark also swallow the trailing space
          let end = nt;
          if (name === "HeaderMark" && state.sliceDoc(nt, nt + 1) === " ") end = nt + 1;
          out.push(Decoration.replace({}).range(nf, end));
          return;
        }
        if (name === "ListMark" && !lineActive(nf)) {
          const ch = state.sliceDoc(nf, nt);
          if (/^[-*+]$/.test(ch)) out.push(Decoration.replace({ widget: new BulletWidget() }).range(nf, nt));
          return;
        }
      },
  });
  return Decoration.set(out, true);
}

const decoField = StateField.define({
  create: (state) => buildDecorations(state),
  update: (deco, tr) => {
    const focusChanged = tr.effects.some((e) => e.is(setFocus));
    return (tr.docChanged || tr.selection || focusChanged) ? buildDecorations(tr.state) : deco;
  },
  provide: (f) => EditorView.decorations.from(f),
});

const atomicRanges = EditorView.atomicRanges.of((view) => view.state.field(decoField, false) || Decoration.none);

// ---------- auto-close code fence ----------
// Typing the 3rd backtick on an otherwise-empty line inserts the matching closing
// fence below and drops the caret on the opening line (so a language can be typed),
// instead of letting the unclosed fence swallow the rest of the document.
const codeFenceInput = EditorView.inputHandler.of((view, from, to, text) => {
  if (text !== "`") return false;
  const line = view.state.doc.lineAt(from);
  const before = view.state.sliceDoc(line.from, from);
  const after = view.state.sliceDoc(to, line.to);
  // line is exactly "``" before the caret, nothing meaningful after
  if (before !== "``" || after.trim() !== "") return false;
  const insert = "`\n\n```";
  view.dispatch({
    changes: { from, to, insert },
    selection: { anchor: from + 1 }, // caret right after the opening ```
    userEvent: "input.type",
  });
  return true;
});

// ---------- theme (colors come from CSS vars) ----------
const baseTheme = EditorView.theme({
  "&": { backgroundColor: "transparent", color: "var(--text)", height: "auto" },
  "&.cm-focused": { outline: "none" },
  ".cm-scroller": { fontFamily: "var(--font-ui)", lineHeight: "1.8", overflow: "visible" },
  ".cm-content": { padding: "0", caretColor: "var(--ink)", maxWidth: "100%", minHeight: "300px" },
  ".cm-line": { padding: "0 0" },
  "&.cm-editor": { height: "auto" },
  ".cm-cursor, .cm-dropCursor": { borderLeftColor: "var(--ink)", borderLeftWidth: "2px" },
  "&.cm-focused .cm-selectionBackground, .cm-selectionBackground, ::selection": { backgroundColor: "var(--sel)" },
  ".cm-placeholder": { color: "var(--text-faint)" },
});

// ---------- public API ----------
const editable = new Compartment();

function create(parent, opts) {
  const { doc = "", onChange, onFocusEditable } = opts || {};
  const state = EditorState.create({
    doc,
    extensions: [
      history(),
      keymap.of([...completionKeymap, { key: "Tab", run: tabInCode, shift: shiftTabInCode }, ...defaultKeymap, ...historyKeymap]),
      markdown({ base: markdownLanguage, extensions: GFM, addKeymap: true, codeLanguages: codeLanguageFor }),
      syntaxHighlighting(codeHighlight),
      autocompletion({ override: [fenceCompletion], activateOnTyping: true, icons: false }),
      EditorView.lineWrapping,
      codeFenceInput,
      focusField,
      decoField,
      atomicRanges,
      baseTheme,
      cmPlaceholder(opts.placeholder || "내용을 입력하세요…  (Enter: 다음 줄 · Shift+Enter: 단락 내 줄바꿈)"),
      EditorView.updateListener.of((u) => {
        if (u.focusChanged) u.view.dispatch({ effects: setFocus.of(u.view.hasFocus) });
        if (u.docChanged && onChange) onChange(u.state.doc.toString());
      }),
    ],
  });
  const view = new EditorView({ state, parent });
  window.__wnView = view;
  return view;
}

function dispatchWrap(view, left, right, ph) {
  const { from, to } = view.state.selection.main;
  const sel = view.state.sliceDoc(from, to) || ph || "";
  view.dispatch({
    changes: { from, to, insert: left + sel + right },
    selection: { anchor: from + left.length, head: from + left.length + sel.length },
  });
  view.focus();
}

function dispatchPrefix(view, prefix) {
  const line = view.state.doc.lineAt(view.state.selection.main.from);
  view.dispatch({ changes: { from: line.from, insert: prefix }, selection: { anchor: view.state.selection.main.from + prefix.length } });
  view.focus();
}

// Heading toggle: replaces any existing leading "#{1,6} " marker; same level → removes it.
function dispatchHeading(view, level) {
  const state = view.state;
  const line = state.doc.lineAt(state.selection.main.from);
  const m = line.text.match(/^(#{1,6})\s+/);
  const want = "#".repeat(level) + " ";
  let insert, removedLen;
  if (m) {
    removedLen = m[0].length;
    insert = (m[1].length === level) ? "" : want; // same level toggles off
  } else {
    removedLen = 0;
    insert = want;
  }
  const caret = state.selection.main.from;
  const newCaret = Math.max(line.from, caret - removedLen + insert.length);
  view.dispatch({ changes: { from: line.from, to: line.from + removedLen, insert }, selection: { anchor: newCaret } });
  view.focus();
}

function dispatchBlock(view, text) {
  const line = view.state.doc.lineAt(view.state.selection.main.from);
  const atEmpty = line.text.trim() === "";
  const insert = (atEmpty ? "" : "\n\n") + text + "\n";
  const pos = atEmpty ? line.from : line.to;
  view.dispatch({ changes: { from: pos, to: atEmpty ? line.to : pos, insert }, selection: { anchor: pos + insert.length } });
  view.focus();
}

window.WN_CM = {
  create,
  wrap: dispatchWrap,
  prefix: dispatchPrefix,
  heading: dispatchHeading,
  block: dispatchBlock,
  focus: (v) => v && v.focus(),
};
window.dispatchEvent(new Event("wn-cm-ready"));
