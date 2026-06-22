/* 관리자 PII 노트 본문 열람 모달 — 원문 raw 가상 윈도잉 + 매치 라인 포커스/네비. */
import React from "react";
import type { ApiPiiContent } from "../admin/api";
import { matchesByLine, splitLineSegments, nextMatchIndex, visibleRange } from "../lib/pii";
import { piiTypeLabel } from "../lib/pii";
import { Icon } from "./Icon";
import "../styles/pii.css";

const { useState, useRef, useMemo, useEffect, useCallback } = React;
const h = React.createElement;

const ROW = 22;          // 라인 고정 높이(px) — pii.css .pii-line과 일치
const VIEWPORT = 380;    // 코드 영역 높이(px) — pii.css .pii-code와 일치

interface Props {
  data: ApiPiiContent;
  source: "request" | "note" | "exempted";
  busy: boolean;
  onApprove: () => void;
  onReject: () => void;
  onNotice: () => void;
  onClose: () => void;
}

export function PiiNoteViewer({ data, source, busy, onApprove, onReject, onNotice, onClose }: Props) {
  const lines = useMemo(() => data.content.split("\n"), [data.content]);
  const byLine = useMemo(() => matchesByLine(data.matches), [data.matches]);
  const total = data.matches.length;

  const codeRef = useRef<HTMLDivElement | null>(null);
  const [scrollTop, setScrollTop] = useState(0);
  const [active, setActive] = useState(0);

  // active 매치가 바뀌면 그 라인을 가운데로 스크롤.
  useEffect(() => {
    const el = codeRef.current;
    if (!el || total === 0) return;
    const line = data.matches[active].line - 1;
    el.scrollTop = Math.max(0, line * ROW - VIEWPORT / 2 + ROW / 2);
  }, [active, total, data.matches]);

  const go = useCallback((dir: 1 | -1) => setActive((c) => nextMatchIndex(c, total, dir)), [total]);

  const { start, end } = visibleRange(scrollTop, VIEWPORT, ROW, lines.length);
  const activeLine = total > 0 ? data.matches[active].line - 1 : -1;

  const rows: React.ReactNode[] = [];
  for (let i = start; i < end; i++) {
    const segs = splitLineSegments(lines[i], byLine.get(i) ?? []);
    rows.push(h("div", { key: i, className: "pii-line" + (i === activeLine ? " active" : "") },
      segs.map((s, k) => s.mark
        ? h("mark", { key: k }, s.text)
        : h("span", { key: k }, s.text))));
  }

  const footer: React.ReactNode[] = [
    h("button", { key: "close", className: "btn", disabled: busy, onClick: onClose }, "닫기"),
  ];
  if (source === "request") {
    footer.push(
      h("button", { key: "reject", className: "btn danger", disabled: busy, onClick: onReject }, "반려"),
      h("button", { key: "approve", className: "btn primary", disabled: busy, onClick: onApprove }, "허용"));
  } else if (source === "note") {
    footer.push(h("button", { key: "notice", className: "btn primary", disabled: busy, onClick: onNotice },
      h(Icon, { name: "check" }), "알림 보내기"));
  }

  return h("div", { className: "modal-ov", onMouseDown: () => { if (!busy) onClose(); } },
    h("div", { className: "modal pii-viewer", onMouseDown: (e: React.MouseEvent) => e.stopPropagation() },
      h("div", { className: "modal-head" },
        h("div", { className: "micon" }, h(Icon, { name: "shield" })),
        h("h3", null, data.title || "노트")),
      h("div", { className: "modal-body" },
        h("div", { className: "pii-nav" },
          total === 0
            ? h("span", { className: "muted" }, "탐지된 항목 없음")
            : h(React.Fragment, null,
                h("span", { className: "muted" },
                  piiTypeLabel(data.matches[active].type) + " · " + (active + 1) + " / " + total),
                h("span", { style: { flex: 1 } }),
                h("button", { className: "btn sm", onClick: () => go(-1) }, "이전"),
                h("button", { className: "btn sm", onClick: () => go(1) }, "다음"))),
        h("div", {
          ref: codeRef, className: "pii-code",
          onScroll: (e: React.UIEvent<HTMLDivElement>) => setScrollTop(e.currentTarget.scrollTop),
        },
          h("div", { style: { height: lines.length * ROW + "px", position: "relative" } },
            h("div", { style: { position: "absolute", top: start * ROW + "px", left: 0, right: 0 } }, rows)))),
      h("div", { className: "modal-foot" }, footer)));
}
