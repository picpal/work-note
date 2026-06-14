import { marked } from "marked";
import hljs from "highlight.js/lib/common";
import mermaid from "mermaid";
import DOMPurify from "dompurify";

// ---- marked config ----
marked.setOptions({ gfm: true, breaks: true });

let mermaidReady = false;
function initMermaid(isDark: boolean): void {
  const v = isDark
    ? { bg: "#1d1c1a", line: "#5f5d57", text: "#dcdbd6", node: "#262521", border: "#3d3b36", alt: "#2c2b27" }
    : { bg: "#fbfbfa", line: "#bdbdb5", text: "#2a2a27", node: "#ffffff", border: "#dcdcd6", alt: "#f1f1ee" };
  mermaid.initialize({
    startOnLoad: false,
    // strict: mermaid가 라벨 HTML을 자체 sanitize — loose면 data-src 경유 원문이 renderMarkdown의
    // DOMPurify를 우회해 enhanceMermaid의 innerHTML 주입으로 XSS 가능 (타인 노트 열람 위협 모델)
    securityLevel: "strict",
    fontFamily: '"Pretendard", system-ui, sans-serif',
    fontSize: 15,
    // htmlLabels:false — strict의 SVG-only DOMPurify가 foreignObject 내부 HTML 라벨을 제거해
    // 도형만 남고 텍스트가 사라지므로, 라벨을 SVG <text>로 렌더해 sanitize를 통과시킨다.
    htmlLabels: false,
    flowchart: { htmlLabels: false, padding: 14, diagramPadding: 10, nodeSpacing: 52, rankSpacing: 50, useMaxWidth: true },
    sequence: { useMaxWidth: true, boxMargin: 12 },
    theme: "base",
    themeVariables: {
      background: v.bg,
      primaryColor: v.node,
      primaryBorderColor: v.border,
      primaryTextColor: v.text,
      lineColor: v.line,
      textColor: v.text,
      mainBkg: v.node,
      nodeBorder: v.border,
      clusterBkg: v.alt,
      clusterBorder: v.border,
      edgeLabelBackground: v.bg,
      // sequence
      actorBkg: v.node,
      actorBorder: v.border,
      actorTextColor: v.text,
      actorLineColor: v.line,
      signalColor: v.text,
      signalTextColor: v.text,
      labelBoxBkgColor: v.alt,
      labelBoxBorderColor: v.border,
      labelTextColor: v.text,
      loopTextColor: v.text,
      noteBkgColor: v.alt,
      noteBorderColor: v.border,
      noteTextColor: v.text,
      sequenceNumberColor: v.text,
      // flow decisions
      tertiaryColor: v.alt,
      tertiaryBorderColor: v.border,
    },
  });
  mermaidReady = true;
}

export function setMermaidTheme(isDark: boolean): void {
  initMermaid(isDark);
  // re-render everything currently on screen
  document.querySelectorAll(".mermaid-wrap").forEach((el) => { (el as HTMLElement).removeAttribute("data-done"); });
  enhanceMermaid(document.body);
}

function escAttr(s: string): string {
  return s.replace(/&/g, "&amp;").replace(/"/g, "&quot;").replace(/</g, "&lt;").replace(/>/g, "&gt;");
}

// 이미지 src를 내부 첨부·상대경로로만 제한 (설계 결정 ②: 외부 콜백/추적 픽셀 차단).
// 허용: "/api/attachments/...", "/api/share/.../attachments/...", 스킴/"//" 없는 상대경로.
// 모듈 1회만 등록(중복 방지 플래그) — DOMPurify는 프로세스 전역 싱글턴이라 다중 등록 시 훅이 누적된다.
let imgHookAdded = false;
function ensureImgHook(): void {
  if (imgHookAdded) return;
  imgHookAdded = true;
  DOMPurify.addHook("afterSanitizeAttributes", (node) => {
    if ((node as Element).tagName !== "IMG") return;
    const src = node.getAttribute("src") || "";
    const internal = src.startsWith("/api/attachments/") || src.startsWith("/api/share/");
    const relative = !/^[a-z][a-z0-9+.-]*:/i.test(src) && !src.startsWith("//"); // 스킴·protocol-relative 아님
    if (!(internal || relative)) node.removeAttribute("src");
  });
}

export function renderMarkdown(src: string): string {
  ensureImgHook();
  let html: string;
  try { html = marked.parse(src || "") as string; } catch (e) { return "<p>" + escAttr(src) + "</p>"; }
  const tpl = document.createElement("template");
  tpl.innerHTML = html;
  const frag = tpl.content;

  // code blocks
  frag.querySelectorAll("pre > code").forEach((code) => {
    const pre = code.parentElement!;
    const cls = (code as HTMLElement).className || "";
    const m = cls.match(/language-([\w-]+)/);
    const lang = m ? m[1].toLowerCase() : "";
    const raw = (code as HTMLElement).textContent || "";
    if (lang === "mermaid") {
      const wrap = document.createElement("div");
      wrap.className = "mermaid-wrap";
      wrap.setAttribute("data-src", encodeURIComponent(raw));
      pre.replaceWith(wrap);
      return;
    }
    // highlight
    try {
      let res: ReturnType<typeof hljs.highlight>;
      if (lang && hljs.getLanguage(lang)) res = hljs.highlight(raw, { language: lang });
      else res = hljs.highlightAuto(raw);
      (code as HTMLElement).innerHTML = res.value;
      (code as HTMLElement).classList.add("hljs");
    } catch (e) { /* leave raw */ }
    if (lang) {
      const tag = document.createElement("span");
      tag.className = "lang-tag";
      tag.textContent = lang;
      pre.appendChild(tag);
    }
  });

  // task list items
  frag.querySelectorAll("li").forEach((li) => {
    const cb = li.querySelector<HTMLInputElement>(":scope > input[type=checkbox]");
    if (!cb) return;
    const done = cb.checked;
    li.classList.add("task-list-item");
    if (done) li.classList.add("done");
    const ul = li.closest("ul");
    if (ul) ul.classList.add("contains-task-list");
    cb.remove();
    // wrap remaining content
    const body = document.createElement("span");
    body.className = "body";
    while (li.firstChild) body.appendChild(li.firstChild);
    const tick = document.createElement("span");
    tick.className = "tick";
    if (done) tick.innerHTML = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="3.5" stroke-linecap="round" stroke-linejoin="round"><polyline points="20 6 9 17 4 12"/></svg>';
    li.appendChild(tick);
    li.appendChild(body);
  });

  // 첨부 링크 → 다운로드 칩 (비이미지). href가 내부 첨부 경로인 a에 클래스 부여(DOMPurify 기본이 class 보존).
  frag.querySelectorAll('a[href^="/api/attachments/"]').forEach((a) => {
    (a as HTMLElement).classList.add("attach-chip");
  });

  // 타인 작성 노트(팀 공유·공유 링크)도 같은 경로로 렌더 — 마크다운 HTML의 stored XSS 차단은 여기서,
  // mermaid 경로(data-src 원문 → enhanceMermaid 렌더)는 securityLevel:"strict"가 담당한다.
  // 기본 정책이 data-*(mermaid-wrap data-src)·hljs 클래스·tick SVG를 보존하고 핸들러·javascript: URI만 제거한다.
  return DOMPurify.sanitize(tpl.innerHTML);
}

export function enhanceMermaid(root: Element | Document): void {
  if (!mermaidReady) initMermaid(document.documentElement.getAttribute("data-theme") === "dark");
  const nodes = root.querySelectorAll(".mermaid-wrap:not([data-done])");
  nodes.forEach((el, i) => {
    const src = decodeURIComponent((el as HTMLElement).getAttribute("data-src") || "");
    (el as HTMLElement).setAttribute("data-done", "1");
    const id = "mmd-" + Date.now() + "-" + Math.floor(Math.random() * 1e6) + "-" + i;
    const doRender = () => {
      try {
        mermaid.render(id, src).then(({ svg }) => { (el as HTMLElement).innerHTML = svg; })
          .catch(() => { (el as HTMLElement).innerHTML = '<pre style="margin:0;font-size:12px;color:var(--text-3)">' + escAttr(src) + "</pre>"; });
      } catch (e) {
        (el as HTMLElement).innerHTML = '<pre style="margin:0;font-size:12px;color:var(--text-3)">' + escAttr(src) + "</pre>";
      }
    };
    // render only after web fonts are ready, so node sizing uses correct metrics
    if (document.fonts && document.fonts.ready) document.fonts.ready.then(doRender); else doRender();
  });
}

// plain-text + highlighted snippet for search
export function mdToText(src: string): string {
  return (src || "")
    .replace(/```[\s\S]*?```/g, " ")
    .replace(/`[^`]*`/g, " ")
    .replace(/[#>*_~\-|]/g, " ")
    .replace(/\[[xX ]\]/g, " ")
    .replace(/\n+/g, " ")
    .replace(/\s+/g, " ")
    .trim();
}
