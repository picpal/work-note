import React from "react";
import { Icon } from "./Icon";
import { useEscClose } from "../state/useEscClose";

const h = React.createElement;

interface LinkWarnDialogProps {
  name: string;
  count: number;
  onConfirm: () => void;
  onCancel: () => void;
}

// 백링크가 있는 노트 삭제 전 정보성 확인 — MoveWarnDialog와 동일 오버레이/카드 마크업.
export function LinkWarnDialog({ name, count, onConfirm, onCancel }: LinkWarnDialogProps) {
  useEscClose(onCancel);
  return h("div", { className: "pf-overlay", onMouseDown: onCancel },
    h("div", { className: "pf-card", onMouseDown: (e: React.MouseEvent) => e.stopPropagation() },
      h("div", { className: "pf-head" },
        h("span", { className: "pf-av" }, h(Icon, { name: "trash" })),
        h("div", { className: "pf-id" },
          h("div", { className: "pf-emp" }, name),
          h("div", { className: "pf-role" }, "노트 삭제")),
        h("button", { className: "icon-btn pf-x", onClick: onCancel, title: "닫기" }, h(Icon, { name: "x" }))),
      h("div", { className: "pf-body" },
        h("div", { className: "pf-sec" },
          h("div", { className: "pf-msg err" }, `${count}개 문서가 이 노트를 참조하고 있습니다. 삭제하면 그 링크들은 '연결할 수 없음'이 됩니다. 휴지통에서 30일 내 복구할 수 있습니다.`),
          h("div", { className: "pf-foot" },
            h("button", { className: "pf-btn", onClick: onCancel }, "취소"),
            h("button", { className: "pf-btn danger", onClick: onConfirm }, "삭제"))))));
}
