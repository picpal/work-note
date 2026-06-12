/* ShareModal — 노트 공유 링크 생성/목록/취소. http 모드 전용(App 컨텍스트 메뉴에서 가드). */
import { useState, useEffect, useCallback } from "react";
import React from "react";
import { Icon } from "./Icon";
import { ShareApi, shareUrl } from "../api/share";
import type { ShareLink, CreateShareBody } from "../api/share";
import { ApiError } from "../api/http";

const h = React.createElement;

/** 클립보드 복사 — 폐쇄망 http = 비보안 컨텍스트라 navigator.clipboard 부재(결정 S17), textarea 폴백. */
function copyText(text: string): Promise<void> {
  if (navigator.clipboard?.writeText) return navigator.clipboard.writeText(text);
  const ta = document.createElement("textarea");
  ta.value = text;
  ta.style.position = "fixed";
  ta.style.opacity = "0";
  document.body.appendChild(ta);
  ta.select();
  document.execCommand("copy");
  document.body.removeChild(ta);
  return Promise.resolve();
}

interface ShareModalProps {
  note: { id: string; name: string };
  onClose: () => void;
  toast: (msg: string, icon?: string) => void;
}

export function ShareModal({ note, onClose, toast }: ShareModalProps) {
  const [links, setLinks] = useState<ShareLink[] | null>(null); // null = 로딩 중
  const [days, setDays] = useState("7");
  const [maxViews, setMaxViews] = useState("");
  const [pins, setPins] = useState("");
  const [busy, setBusy] = useState(false);

  const reload = useCallback(async () => {
    try {
      setLinks(await ShareApi.listForNode(note.id));
    } catch (e) {
      setLinks([]);
      toast(e instanceof ApiError ? e.message : "공유 링크를 불러오지 못했습니다");
    }
  }, [note.id, toast]);
  useEffect(() => { void reload(); }, [reload]);

  useEffect(() => {
    const handler = (e: KeyboardEvent) => {
      if (e.key === "Escape") { e.preventDefault(); onClose(); }
    };
    document.addEventListener("keydown", handler);
    return () => document.removeEventListener("keydown", handler);
  }, [onClose]);

  /** admin Users.tsx run() 패턴 — busy 가드, 성공 토스트, 실패 시 서버 메시지 토스트(폼 유지). */
  const run = async (fn: () => Promise<unknown>, okMsg: string, icon?: string): Promise<boolean> => {
    if (busy) return false;
    setBusy(true);
    try {
      await fn();
      await reload();
      toast(okMsg, icon);
      return true;
    } catch (e) {
      toast(e instanceof ApiError ? e.message : "요청 실패");
      return false;
    } finally {
      setBusy(false);
    }
  };

  const copy = (token: string) => {
    void copyText(shareUrl(token)).then(() => toast("링크를 복사했습니다", "clipboard"));
  };
  const revoke = (id: string) => { void run(() => ShareApi.revoke(id), "공유 링크를 취소했습니다", "trash"); };
  const create = () => {
    const body: CreateShareBody = {};
    if (days.trim() !== "") body.days = Number(days);
    if (maxViews.trim() !== "") body.maxViews = Number(maxViews);
    const emps = pins.split(",").map((s) => s.trim()).filter(Boolean);
    if (emps.length) body.pinEmps = emps;
    void run(async () => {
      const res = await ShareApi.create(note.id, body);
      await copyText(shareUrl(res.token));
    }, "공유 링크를 만들어 복사했습니다", "check");
  };

  return h("div", { className: "pf-overlay", onMouseDown: onClose },
    h("div", { className: "pf-card", onMouseDown: (e: React.MouseEvent) => e.stopPropagation() },
      h("div", { className: "pf-head" },
        h("span", { className: "pf-av" }, h(Icon, { name: "link" })),
        h("div", { className: "pf-id" },
          h("div", { className: "pf-emp" }, note.name),
          h("div", { className: "pf-role" }, "공유 링크")),
        h("button", { className: "icon-btn pf-x", onClick: onClose, title: "닫기" }, h(Icon, { name: "x" }))),
      h("div", { className: "pf-body" },
        // 활성 링크 목록
        h("div", { className: "pf-sec" },
          h("div", { className: "pf-sec-label" }, "활성 링크"),
          links == null
            ? h("div", { className: "sh-empty" }, "불러오는 중…")
            : links.length === 0
              ? h("div", { className: "sh-empty" }, "활성 링크가 없습니다")
              : links.map((l) =>
                  h("div", { className: "sh-row", key: l.id },
                    h("div", { className: "sh-meta" },
                      h("div", { className: "sh-exp" }, "~" + l.expiresAt.slice(0, 10)),
                      h("div", { className: "sh-sub" },
                        "열람 " + l.viewCount + " / " + (l.maxViews ?? "∞") +
                        " · " + (l.pinEmps?.join(", ") ?? "전 직원"))),
                    h("div", { className: "sh-act" },
                      h("button", { className: "pf-btn", onClick: () => copy(l.token) }, "복사"),
                      h("button", { className: "pf-btn", disabled: busy, onClick: () => revoke(l.id) }, "취소"))))),
        // 생성 폼
        h("div", { className: "pf-sec" },
          h("div", { className: "pf-sec-label" }, "새 링크"),
          h("div", { className: "pf-field" },
            h("label", null, "만료 일수"),
            h("input", { className: "pf-input", type: "number", min: 1, value: days,
              onChange: (e: React.ChangeEvent<HTMLInputElement>) => setDays(e.target.value) })),
          h("div", { className: "pf-field" },
            h("label", null, "최대 열람수"),
            h("input", { className: "pf-input", type: "number", min: 1, value: maxViews, placeholder: "비우면 무제한",
              onChange: (e: React.ChangeEvent<HTMLInputElement>) => setMaxViews(e.target.value) })),
          h("div", { className: "pf-field" },
            h("label", null, "대상 사번 (콤마 구분)"),
            h("input", { className: "pf-input", value: pins, placeholder: "비우면 전 직원",
              onChange: (e: React.ChangeEvent<HTMLInputElement>) => setPins(e.target.value) })),
          h("div", { className: "pf-msg ok" }, "링크는 로그인한 직원만 열 수 있으며 read 전용입니다."),
          h("div", { className: "pf-foot" },
            h("button", { className: "pf-btn primary", disabled: busy, onClick: create }, "링크 만들기"))))));
}
