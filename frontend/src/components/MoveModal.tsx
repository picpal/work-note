/* MoveModal — 이동 목적지 피커(루트 + 폴더, 검색) + 노출 경고. 헤더에 대상명·현재 부모 경로 표시.
   http 모드는 move-preview로 노출 변화 경고, local 모드는 즉시 이동.
   목적지에는 "루트 (최상위)"가 포함된다 — 루트도 정상적인 위치이므로 되돌아갈 수 있어야 한다(D-6).
   루트 선택도 target=null로 같은 onMoveClick 경로를 타므로 move-preview 경고를 우회하지 않는다. */
import { useState, useEffect } from "react";
import React from "react";
import { Icon } from "./Icon";
import { VaultApi } from "../storage/VaultApi";
import type { MovePreview } from "../storage/VaultApi";
import { ApiError } from "../api/http";
import { storageMode } from "../storage";
import { shouldWarn } from "./moveWarning";
import { MoveWarnContent } from "./MoveWarnDialog";
import { findNode } from "../lib/tree";
import { moveTargets, filterMoveTargets, isCurrentTarget, type MoveTarget } from "./moveTargets";
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
  const [target, setTarget] = useState<string | null>(null); // 선택된 폴더 id
  const [selected, setSelected] = useState(false);
  const [preview, setPreview] = useState<MovePreview | null>(null);
  const [busy, setBusy] = useState(false);
  const [query, setQuery] = useState("");                    // 폴더 검색어

  const found = findNode(tree, node.id);
  const currentParentId = found.parentNode?.id ?? null;      // 현재 부모(루트면 null) — 같은 위치 이동 비활성
  const nodeType = found.node?.type ?? "note";               // 이동 대상 타입(헤더 아이콘)
  const parentPath = found.path.length ? found.path.join(" / ") : "루트 (최상위)"; // 헤더에 표시할 부모 디렉토리
  const options = moveTargets(tree, node.id);              // [0] = 루트(최상위)
  const filtered = filterMoveTargets(options, query);

  useEffect(() => {
    const handler = (e: KeyboardEvent) => {
      if (e.key === "Escape") { e.preventDefault(); onClose(); }
    };
    document.addEventListener("keydown", handler);
    return () => document.removeEventListener("keydown", handler);
  }, [onClose]);

  const pick = (t: MoveTarget) => {
    if (isCurrentTarget(t, currentParentId)) { toast("같은 위치입니다"); return; }
    setTarget(t.parentId);
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

  const body =
    phase === "pick"
      ? h("div", { className: "pf-sec" },
          h("div", { className: "pf-sec-label" }, "이동할 위치"),
          h("input", {
            className: "mv-search",
            type: "text",
            placeholder: "폴더 이름·경로 검색…",
            value: query,
            autoFocus: true,
            onChange: (e: React.ChangeEvent<HTMLInputElement>) => setQuery(e.target.value),
          }),
          h("div", { className: "mv-list" },
            filtered.length === 0
              ? h("div", { style: { padding: "14px", color: "var(--text-3)", fontSize: 13 } },
                  "검색 결과가 없습니다")
              : filtered.map((o) =>
                  h("button", {
                    key: o.key,
                    className: "mv-opt" + (o.kind === "root" ? " root" : "") + (selected && target === o.parentId ? " sel" : ""),
                    disabled: isCurrentTarget(o, currentParentId),
                    onClick: () => pick(o),
                  },
                    h("span", { className: "ic" }, h(Icon, { name: o.kind === "root" ? "book" : o.kind === "space" ? "space" : "folder" })),
                    h("span", { className: "lbl" }, o.label),
                    isCurrentTarget(o, currentParentId) ? h("span", { className: "here" }, "현재 위치") : null))),
          h("div", { className: "pf-foot" },
            h("button", { className: "pf-btn", onClick: onClose }, "취소"),
            h("button", { className: "pf-btn primary", disabled: !selected || busy, onClick: onMoveClick }, "이동")))
      : h("div", { className: "pf-sec" },
          h(MoveWarnContent, { preview: preview! }),
          h("div", { className: "pf-foot" },
            h("button", { className: "pf-btn", onClick: () => setPhase("pick") }, "취소"),
            h("button", { className: "pf-btn danger", disabled: busy, onClick: doMove }, "이동")));

  return h("div", { className: "pf-overlay", onMouseDown: onClose },
    h("div", { className: "pf-card mv-modal", onMouseDown: (e: React.MouseEvent) => e.stopPropagation() },
      h("div", { className: "pf-head" },
        h("span", { className: "pf-av" }, h(Icon, { name: nodeType === "folder" ? "folder" : "fileLines" })),
        h("div", { className: "pf-id" },
          h("div", { className: "pf-emp" }, node.name),
          h("div", { className: "pf-role mv-srcpath", title: parentPath }, parentPath)),
        h("button", { className: "icon-btn pf-x", onClick: onClose, title: "닫기" }, h(Icon, { name: "x" }))),
      h("div", { className: "pf-body" }, body)));
}
