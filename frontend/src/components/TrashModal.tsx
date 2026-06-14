/* TrashModal — 휴지통: soft-delete된 노트/폴더 목록 + 복구/영구 삭제. server(http) 모드 전용.
   백엔드 GET /trash · POST /trash/{id}/restore · DELETE /trash/{id} 에 연결. */
import { useState, useEffect, useCallback } from "react";
import React from "react";
import { Icon } from "./Icon";
import { VaultApi } from "../storage/VaultApi";
import type { TrashItem } from "../storage/VaultApi";
import { ApiError } from "../api/http";

const h = React.createElement;

interface TrashModalProps {
  onClose: () => void;
  toast: (msg: string, icon?: string) => void;
  onRestored: () => void; // 복구 후 트리 재동기화(useVault.reload)
}

export function TrashModal({ onClose, toast, onRestored }: TrashModalProps) {
  const [items, setItems] = useState<TrashItem[] | null>(null); // null = 로딩 중
  const [busy, setBusy] = useState(false);
  const [confirmId, setConfirmId] = useState<string | null>(null); // 영구 삭제 확인 대상

  const reload = useCallback(async () => {
    try { setItems(await VaultApi.trashList()); }
    catch { setItems([]); }
  }, []);
  useEffect(() => { void reload(); }, [reload]);

  useEffect(() => {
    const handler = (e: KeyboardEvent) => { if (e.key === "Escape") onClose(); };
    document.addEventListener("keydown", handler);
    return () => document.removeEventListener("keydown", handler);
  }, [onClose]);

  /** ShareModal.run() 패턴 — busy 가드, 목록 재조회, 성공 토스트, 실패 시 서버 메시지. */
  const run = async (fn: () => Promise<unknown>, okMsg: string, icon: string, refreshTree: boolean): Promise<void> => {
    if (busy) return;
    setBusy(true);
    setConfirmId(null);
    try {
      await fn();
      await reload();
      if (refreshTree) onRestored();
      toast(okMsg, icon);
    } catch (e) {
      toast(e instanceof ApiError ? e.message : "요청 실패");
    } finally {
      setBusy(false);
    }
  };
  const restore = (id: string) => { void run(() => VaultApi.restore(id), "복구했습니다", "check", true); };
  const purge = (id: string) => { void run(() => VaultApi.purge(id), "영구 삭제했습니다", "trash", false); };

  const label = (it: TrashItem) => it.title ?? it.name ?? "(이름 없음)";

  return h("div", { className: "pf-overlay", onMouseDown: onClose },
    h("div", { className: "pf-card", onMouseDown: (e: React.MouseEvent) => e.stopPropagation() },
      h("div", { className: "pf-head" },
        h("span", { className: "pf-av" }, h(Icon, { name: "trash" })),
        h("div", { className: "pf-id" },
          h("div", { className: "pf-emp" }, "휴지통"),
          h("div", { className: "pf-role" }, "삭제한 노트·폴더 (30일 후 자동 영구 삭제)")),
        h("button", { className: "icon-btn pf-x", onClick: onClose, title: "닫기" }, h(Icon, { name: "x" }))),
      h("div", { className: "pf-body" },
        h("div", { className: "pf-sec" },
          items == null
            ? h("div", { className: "sh-empty" }, "불러오는 중…")
            : items.length === 0
              ? h("div", { className: "sh-empty" }, "휴지통이 비어 있습니다")
              : items.map((it) =>
                  h("div", { className: "sh-row", key: it.id },
                    h("div", { className: "sh-meta" },
                      h("div", { className: "sh-exp" }, label(it)),
                      h("div", { className: "sh-sub" }, it.type === "folder" ? "폴더" : "노트")),
                    h("div", { className: "sh-act" },
                      confirmId === it.id
                        ? h(React.Fragment, null,
                            h("button", { className: "pf-btn danger", disabled: busy, onClick: () => purge(it.id) }, "영구 삭제 확인"),
                            h("button", { className: "pf-btn", disabled: busy, onClick: () => setConfirmId(null) }, "취소"))
                        : h(React.Fragment, null,
                            h("button", { className: "pf-btn primary", disabled: busy, onClick: () => restore(it.id) }, "복구"),
                            h("button", { className: "pf-btn", disabled: busy, onClick: () => setConfirmId(it.id) }, "영구 삭제")))))))));
}
