/* MoveModal — 이동 폴더 피커 + 노출 경고. http 모드는 move-preview로 노출 변화 경고, local 모드는 즉시 이동. */
import { useState, useEffect } from "react";
import React from "react";
import { Icon } from "./Icon";
import { VaultApi } from "../storage/VaultApi";
import type { MovePreview } from "../storage/VaultApi";
import { ApiError } from "../api/http";
import { storageMode } from "../storage";
import { shouldWarn } from "./moveWarning";
import { folderOptions, findNode } from "../lib/tree";
import type { VaultTree } from "../types";

const h = React.createElement;

interface MoveModalProps {
  node: { id: string; name: string };
  tree: VaultTree;
  onMove: (id: string, parentId: string | null) => void;
  onClose: () => void;
  toast: (msg: string, icon?: string) => void;
}

export function MoveModal({ node, tree, onMove, onClose, toast }: MoveModalProps) {
  const [phase, setPhase] = useState<"pick" | "warn">("pick");
  const [target, setTarget] = useState<string | null>(null); // 선택된 폴더 id, null = 루트
  const [selected, setSelected] = useState(false);           // null 도 유효 선택이므로 별도 플래그
  const [preview, setPreview] = useState<MovePreview | null>(null);
  const [busy, setBusy] = useState(false);

  // 현재 부모 id (루트면 null) — 같은 위치로의 이동은 무의미하므로 비활성.
  const currentParentId = findNode(tree, node.id).parentNode?.id ?? null;
  const options = folderOptions(tree, node.id);

  useEffect(() => {
    const handler = (e: KeyboardEvent) => {
      if (e.key === "Escape") { e.preventDefault(); onClose(); }
    };
    document.addEventListener("keydown", handler);
    return () => document.removeEventListener("keydown", handler);
  }, [onClose]);

  const pick = (id: string | null) => {
    if (id === currentParentId) { toast("같은 위치입니다"); return; }
    setTarget(id);
    setSelected(true);
  };

  const doMove = () => {
    onMove(node.id, target);
    toast("이동했습니다", "check");
    onClose();
  };

  const onMoveClick = async () => {
    if (busy) return;
    if (storageMode !== "http") { doMove(); return; }
    setBusy(true);
    try {
      const p = await VaultApi.movePreview(node.id, target);
      const w = shouldWarn(p);
      if (!w.warn) { doMove(); return; }
      setPreview(p);
      setPhase("warn");
    } catch (e) {
      toast(e instanceof ApiError ? e.message : "이동할 수 없습니다");
    } finally {
      setBusy(false);
    }
  };

  const warn = preview ? shouldWarn(preview) : null;

  const body =
    phase === "pick"
      ? h("div", { className: "pf-sec" },
          h("div", { className: "pf-sec-label" }, "이동할 위치"),
          h("div", { className: "mv-list" },
            h("button", {
              className: "mv-opt" + (selected && target === null ? " sel" : ""),
              disabled: currentParentId === null,
              onClick: () => pick(null),
            },
              h("span", { className: "ic" }, h(Icon, { name: "folderOpen" })),
              h("span", { className: "lbl" }, "루트 (최상위)"),
              currentParentId === null ? h("span", { className: "here" }, "현재 위치") : null),
            options.map((o) =>
              h("button", {
                key: o.id,
                className: "mv-opt" + (selected && target === o.id ? " sel" : ""),
                disabled: o.id === currentParentId,
                onClick: () => pick(o.id),
              },
                h("span", { className: "ic" }, h(Icon, { name: "folder" })),
                h("span", { className: "lbl" }, o.label),
                o.id === currentParentId ? h("span", { className: "here" }, "현재 위치") : null))),
          h("div", { className: "pf-foot" },
            h("button", { className: "pf-btn", onClick: onClose }, "취소"),
            h("button", { className: "pf-btn primary", disabled: !selected || busy, onClick: onMoveClick }, "이동")))
      : h("div", { className: "pf-sec" },
          h("div", { className: "pf-sec-label" }, "이동 시 변경 사항"),
          (warn?.lines ?? []).map((line, i) =>
            h("div", { className: "mv-warn-line", key: i }, line)),
          h("div", { className: "pf-msg " + (warn?.strong ? "err" : "ok") },
            warn?.strong ? "노출 범위가 넓어집니다. 계속하시겠습니까?" : "계속하시겠습니까?"),
          h("div", { className: "pf-foot" },
            h("button", { className: "pf-btn", onClick: () => setPhase("pick") }, "취소"),
            h("button", { className: "pf-btn danger", disabled: busy, onClick: doMove }, "이동")));

  return h("div", { className: "pf-overlay", onMouseDown: onClose },
    h("div", { className: "pf-card", onMouseDown: (e: React.MouseEvent) => e.stopPropagation() },
      h("div", { className: "pf-head" },
        h("span", { className: "pf-av" }, h(Icon, { name: "move" })),
        h("div", { className: "pf-id" },
          h("div", { className: "pf-emp" }, node.name),
          h("div", { className: "pf-role" }, "이동")),
        h("button", { className: "icon-btn pf-x", onClick: onClose, title: "닫기" }, h(Icon, { name: "x" }))),
      h("div", { className: "pf-body" }, body)));
}
