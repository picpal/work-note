/* MoveWarnDialog — 이동 노출 경고 UI. MoveModal(피커 내부)와 DnD(독립 오버레이)가 공유. */
import React from "react";
import { Icon } from "./Icon";
import type { MovePreview } from "../storage/VaultApi";
import { shouldWarn } from "./moveWarning";
import { useEscClose } from "../state/useEscClose";

const h = React.createElement;

/** 경고 본문(라벨 + 변경 라인 + 메시지). 푸터 버튼은 호출측이 별도 구성. */
export function MoveWarnContent({ preview }: { preview: MovePreview }) {
  const warn = shouldWarn(preview);
  return h(React.Fragment, null,
    h("div", { className: "pf-sec-label" }, "이동 시 변경 사항"),
    (warn.lines ?? []).map((line, i) => h("div", { className: "mv-warn-line", key: i }, line)),
    h("div", { className: "pf-msg " + (warn.strong ? "err" : "ok") },
      warn.strong ? "노출 범위가 넓어집니다. 계속하시겠습니까?" : "계속하시겠습니까?"));
}

interface MoveWarnDialogProps {
  name: string;
  preview: MovePreview;
  onConfirm: () => void;
  onCancel: () => void;
}

/** 독립 오버레이 경고 다이얼로그 — DnD 드롭 경로용. */
export function MoveWarnDialog({ name, preview, onConfirm, onCancel }: MoveWarnDialogProps) {
  useEscClose(onCancel);
  return h("div", { className: "pf-overlay", onMouseDown: onCancel },
    h("div", { className: "pf-card", onMouseDown: (e: React.MouseEvent) => e.stopPropagation() },
      h("div", { className: "pf-head" },
        h("span", { className: "pf-av" }, h(Icon, { name: "move" })),
        h("div", { className: "pf-id" },
          h("div", { className: "pf-emp" }, name),
          h("div", { className: "pf-role" }, "이동")),
        h("button", { className: "icon-btn pf-x", onClick: onCancel, title: "닫기" }, h(Icon, { name: "x" }))),
      h("div", { className: "pf-body" },
        h("div", { className: "pf-sec" },
          h(MoveWarnContent, { preview }),
          h("div", { className: "pf-foot" },
            h("button", { className: "pf-btn", onClick: onCancel }, "취소"),
            h("button", { className: "pf-btn danger", onClick: onConfirm }, "이동"))))));
}
