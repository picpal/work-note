/* PiiNoticeModal — 로그인 직후 미확인 PII 알림을 팝업으로 표시. 확인 시 ack.
   기존 모달(ShareModal/ProfileModal)의 pf-* 클래스 구조를 그대로 재사용 — 프레임/버튼 스타일 일관. */
import { useState, useEffect } from "react";
import React from "react";
import { PiiApi, type PiiNotice } from "../storage/PiiApi";
import { Icon } from "./Icon";

const h = React.createElement;

const KIND_LABEL: Record<string, string> = {
  flagged: "개인정보가 감지되었습니다",
  approved: "예외 요청이 허용되었습니다",
  rejected: "예외 요청이 반려되었습니다",
};

export function PiiNoticeModal() {
  const [notices, setNotices] = useState<PiiNotice[] | null>(null);

  useEffect(() => {
    let alive = true;
    PiiApi.myNotices().then((n) => { if (alive && n.length) setNotices(n); }).catch(() => {});
    return () => { alive = false; };
  }, []);

  if (!notices || notices.length === 0) return null;

  const close = () => {
    void PiiApi.ackNotices().catch(() => {});
    setNotices(null);
  };

  const groups: Record<string, PiiNotice[]> = {};
  for (const n of notices) (groups[n.kind] ||= []).push(n);

  return h("div", { className: "pf-overlay", onMouseDown: close },
    h("div", { className: "pf-card pii-notice-modal", onMouseDown: (e: React.MouseEvent) => e.stopPropagation() },
      h("div", { className: "pf-head" },
        h("span", { className: "pf-av pii-av" }, h(Icon, { name: "alert" })),
        h("div", { className: "pf-id" },
          h("div", { className: "pf-emp" }, "개인정보 알림"),
          h("div", { className: "pf-role" }, "확인하지 않은 알림 " + notices.length + "건")),
        h("button", { className: "icon-btn pf-x", onClick: close, title: "닫기" }, h(Icon, { name: "x" }))),
      h("div", { className: "pf-body" },
        Object.entries(groups).map(([kind, items]) =>
          h("div", { key: kind, className: "pii-notice-group" },
            h("div", { className: "pii-notice-kind" }, KIND_LABEL[kind] || kind),
            h("ul", { className: "pii-notice-list" },
              items.map((it) =>
                h("li", { key: it.id },
                  h("span", { className: "pii-notice-title" }, it.noteTitle || "(제목 없음)"),
                  it.message ? h("span", { className: "pii-notice-reason" }, " — " + it.message) : null)))))),
      h("div", { className: "pf-foot pii-notice-foot" },
        h("button", { className: "pf-btn primary", onClick: close }, "확인"))));
}
