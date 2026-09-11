/* ShareModal — 노트 공유 링크 생성/목록/취소. http 모드 전용(App 컨텍스트 메뉴에서 가드). */
import { useState, useEffect, useCallback } from "react";
import React from "react";
import { Icon } from "./Icon";
import { ConfirmDialog } from "./ConfirmDialog";
import { ShareApi, shareUrl } from "../api/share";
import type { ShareLink, CreateShareBody } from "../api/share";
import { ApiError } from "../api/http";
import { UserPicker } from "./UserPicker";
import { UserApi } from "../api/users";
import type { DirectoryUser } from "../api/users";
import { shareFlushGate } from "../state/syncStatus";
import type { FlushResult } from "../state/syncStatus";

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

/** 되돌릴 수 없는 동작 앞의 확인 단계 — MoveWarnDialog/LinkWarnDialog와 동일한 오버레이·카드 마크업.
    공용 컴포넌트로 빼지 않는 이유: 이 모달 위에 겹쳐 뜨는 전용 확인이라 형제로 렌더해야 한다
    (ShareModal 카드 안에 넣으면 바깥 오버레이의 mousedown-닫기에 걸린다). */
/* 확인 모달은 공용 ConfirmDialog를 쓴다 — 앱 전체가 같은 확인 방식이어야 한다(P-1). */
interface ConfirmSpec {
  icon: string;
  title: string;
  body: string;
  danger: boolean;
  confirmLabel: string;
  cancelLabel?: string;   // 기본 "취소" — 취소 액션을 확인할 때는 문구가 겹치므로 "돌아가기"를 쓴다
  onConfirm: () => void;
}

interface ShareModalProps {
  note: { id: string; name: string };
  onClose: () => void;
  toast: (msg: string, icon?: string) => void;
  /** 미저장 편집 강제 저장(⌘S와 같은 경로). 링크 생성 직전에 호출한다 — 안 하면 받는 사람이 빈 노트를 본다. */
  flush: () => Promise<FlushResult>;
}

export function ShareModal({ note, onClose, toast, flush }: ShareModalProps) {
  const [links, setLinks] = useState<ShareLink[] | null>(null); // null = 로딩 중
  const [days, setDays] = useState("7");
  const [maxViews, setMaxViews] = useState("");
  const [pinEmps, setPinEmps] = useState<string[]>([]);
  const [directory, setDirectory] = useState<DirectoryUser[] | null>(null);
  const [dirError, setDirError] = useState(false);
  const [busy, setBusy] = useState(false);
  const [confirm, setConfirm] = useState<ConfirmSpec | null>(null);

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
    let alive = true;
    UserApi.directory()
      .then((d) => { if (alive) setDirectory(d); })
      .catch(() => { if (alive) { setDirectory([]); setDirError(true); } });
    return () => { alive = false; };
  }, []);

  useEffect(() => {
    const handler = (e: KeyboardEvent) => {
      if (e.key !== "Escape") return;
      e.preventDefault();
      if (confirm) setConfirm(null);   // 확인 단계가 떠 있으면 그것부터 닫는다
      else onClose();
    };
    document.addEventListener("keydown", handler);
    return () => document.removeEventListener("keydown", handler);
  }, [onClose, confirm]);

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

  // 취소는 되돌릴 수 없고 '복사' 옆 같은 크기 버튼이라 오조작 위험이 크다 → 확인 단계.
  const askRevoke = (l: ShareLink) => {
    setConfirm({
      icon: "trash",
      title: "공유 링크 취소",
      body: `이 링크(~${l.expiresAt.slice(0, 10)}, 열람 ${l.viewCount}회)를 지금 무효화합니다. ` +
        "이미 전달한 사람은 더 이상 열 수 없고, 되돌릴 수 없습니다.",
      danger: true,
      confirmLabel: "링크 취소",
      cancelLabel: "돌아가기",
      onConfirm: () => { setConfirm(null); void run(() => ShareApi.revoke(l.id), "공유 링크를 취소했습니다", "trash"); },
    });
  };

  const doCreate = () => {
    const body: CreateShareBody = {};
    if (days.trim() !== "") body.days = Number(days);
    if (maxViews.trim() !== "") body.maxViews = Number(maxViews);
    if (pinEmps.length) body.pinEmps = pinEmps;
    void run(async () => {
      const res = await ShareApi.create(note.id, body);
      // 클립보드 복사는 best-effort — 실패(보안 컨텍스트 미포커스/권한 거부)해도
      // 링크 생성 성공·목록 갱신을 막지 않는다. 복사는 목록의 복사 버튼으로 재시도 가능.
      try { await copyText(shareUrl(res.token)); } catch { /* noop */ }
    }, "공유 링크를 만들어 복사했습니다", "check");
  };

  // 생성 직전 강제 flush — 디바운스(1분) 대기 중인 본문이 서버에 없으면 수신자가 빈 노트를 본다(D-3).
  const create = async () => {
    if (busy) return;
    setBusy(true);
    let result: FlushResult;
    try {
      result = await flush();
    } catch (e) {   // flush는 스스로 삼키지만, 예외가 새어 나와도 조용히 만들지는 않는다
      result = { ok: false, unsynced: 1, error: e instanceof Error ? e.message : String(e) };
    } finally {
      setBusy(false);
    }
    const gate = shareFlushGate(result);
    if (gate.proceed) { doCreate(); return; }
    setConfirm({
      icon: "alert",
      title: "저장되지 않은 변경",
      body: gate.message!,
      danger: false,
      confirmLabel: "그래도 만들기",
      onConfirm: () => { setConfirm(null); doCreate(); },
    });
  };

  return h(React.Fragment, null,
    h("div", { className: "pf-overlay", onMouseDown: onClose, key: "share" },
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
                        h("button", { className: "pf-btn", disabled: busy, onClick: () => askRevoke(l) }, "취소"))))),
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
              h("label", null, "대상 (비우면 전 직원)"),
              h(UserPicker, { value: pinEmps, onChange: setPinEmps, directory, loadError: dirError })),
            h("div", { className: "pf-msg ok" }, "링크는 로그인한 직원만 열 수 있으며 read 전용입니다."),
            h("div", { className: "pf-foot" },
              h("button", { className: "pf-btn primary", disabled: busy, onClick: () => { void create(); } }, "링크 만들기"))))),
    ),
    confirm && h(ConfirmDialog, {
      key: "confirm", name: note.name, action: confirm.title, message: confirm.body,
      danger: confirm.danger, icon: confirm.icon,
      confirmLabel: confirm.confirmLabel, cancelLabel: confirm.cancelLabel,
      onConfirm: confirm.onConfirm, onCancel: () => setConfirm(null),
    }));
}
