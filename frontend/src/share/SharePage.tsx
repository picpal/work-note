/* SharePage — 공유 토큰 read-only 열람(share.html 단독 엔트리, login/admin과 같은 멀티엔트리).
   401은 리다이렉트하지 않고 상태 화면으로 안내한다(결정 S12) — 리다이렉트하면 로그인 후 링크 복귀 불가. */
import { useState, useEffect, useRef } from "react";
import React from "react";
import { Icon } from "../components/Icon";
import { ShareApi } from "../api/share";
import type { ShareView } from "../api/share";
import { ApiError } from "../api/http";
import { renderMarkdown, enhanceMermaid, setMermaidTheme } from "../lib/markdown";

const h = React.createElement;

type State =
  | { kind: "loading" }
  | { kind: "ok"; view: ShareView }
  | { kind: "unauthorized" } // 401 — PIN 사번 제한 등, 로그인하면 열 수 있음
  | { kind: "invalid" }; // 404 등 — 만료·취소·잘못된 토큰

/** ISO 타임스탬프 → "YYYY-MM-DD HH:mm" — 그 외 포맷은 원문 그대로. */
function fmtStamp(s: string): string {
  return /^\d{4}-\d{2}-\d{2}T/.test(s) ? s.slice(0, 16).replace("T", " ") : s;
}

function ThemeBtn() {
  const [dark, setDark] = useState(document.documentElement.getAttribute("data-theme") === "dark");
  const toggle = () => {
    const next = dark ? "light" : "dark";
    document.documentElement.setAttribute("data-theme", next);
    try { localStorage.setItem("wn.theme", next); } catch (e) {}
    setMermaidTheme(next === "dark"); // Editor와 동일 — 테마 플립 시 mermaid 재초기화+재렌더
    setDark(!dark);
  };
  return h("button", { className: "icon-btn share-theme", title: dark ? "라이트 모드" : "다크 모드", onClick: toggle },
    h(Icon, { name: dark ? "sun" : "moon" }));
}

function StateCard(props: { icon: string; title: string; desc: string; action?: { label: string; onClick: () => void } }) {
  return h("div", { className: "share-state" },
    h("div", { className: "card" },
      h("div", { className: "ic" }, h(Icon, { name: props.icon })),
      h("h2", null, props.title),
      h("p", null, props.desc),
      props.action && h("button", { className: "share-btn", onClick: props.action.onClick }, props.action.label)));
}

export function SharePage() {
  const [state, setState] = useState<State>({ kind: "loading" });
  const bodyRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    const token = new URLSearchParams(location.search).get("token");
    if (!token) { setState({ kind: "invalid" }); return; }
    ShareApi.view(token)
      .then((view) => setState({ kind: "ok", view }))
      .catch((e) => setState({ kind: e instanceof ApiError && e.status === 401 ? "unauthorized" : "invalid" }));
  }, []);

  // 렌더 후 mermaid SVG 치환 — Editor와 동일하게 markdown 결과 안의 .mermaid-wrap을 enhance
  useEffect(() => {
    if (state.kind !== "ok") return;
    document.title = "WorkNote · " + state.view.name;
    if (!bodyRef.current) return;
    enhanceMermaid(bodyRef.current);
    // 공유 열람자는 노트 read 권한이 없으므로 첨부 이미지를 토큰 스코프 엔드포인트로 재지정.
    const token = new URLSearchParams(location.search).get("token");
    if (token) {
      bodyRef.current.querySelectorAll<HTMLImageElement>('img[src^="/api/attachments/"]').forEach((img) => {
        const id = img.getAttribute("src")!.slice("/api/attachments/".length);
        img.setAttribute("src", `/api/share/${encodeURIComponent(token)}/attachments/${encodeURIComponent(id)}`);
      });
    }
  }, [state]);

  let body: React.ReactNode;
  if (state.kind === "loading") {
    body = h("div", { className: "share-state" }, h("div", { className: "share-loading" }, "불러오는 중…"));
  } else if (state.kind === "unauthorized") {
    body = h(StateCard, {
      icon: "shield",
      title: "로그인이 필요한 링크입니다",
      desc: "이 공유 링크는 지정된 사용자만 열 수 있습니다. 로그인한 뒤 이 링크를 다시 열어주세요.",
      action: { label: "로그인하러 가기", onClick: () => { location.href = "login.html"; } },
    });
  } else if (state.kind === "invalid") {
    body = h(StateCard, {
      icon: "x",
      title: "열 수 없는 링크입니다",
      desc: "링크가 만료되었거나 취소되었을 수 있습니다. 노트를 공유한 사람에게 새 링크를 요청하세요.",
    });
  } else {
    const { view } = state;
    body = h("div", { className: "share-wrap fade-key" },
      h("header", { className: "share-head" },
        h("div", { className: "share-brand" },
          h("div", { className: "brand-mark" }, "W"),
          h("span", { className: "nm" }, "WorkNote"),
          h("span", { className: "share-badge" }, "읽기 전용 공유")),
        h("h1", { className: "share-title" }, view.name),
        view.updatedAt && h("div", { className: "share-sub" }, "마지막 수정 " + fmtStamp(view.updatedAt))),
      h("div", {
        className: "md share-body", ref: bodyRef,
        dangerouslySetInnerHTML: { __html: renderMarkdown(view.content || "") },
      }));
  }

  return h("div", { className: "share" }, h(ThemeBtn), body);
}
