/* ConfirmDialog — 파괴적/주의가 필요한 동작의 공용 확인 모달.
   네이티브 window.confirm은 모노톤 디자인·다크모드와 이질적이고 "localhost:8341 says" 출처 문구가
   그대로 노출돼, 앱 모달(LinkWarnDialog·MoveWarnDialog)의 시각 언어로 통일한다.
   마크업은 그 둘과 동일한 .pf-overlay > .pf-card 셸 — 새 디자인을 만들지 않는다.
   JSX 미사용 — h = createElement 관례. */
import React from "react";
import { Icon } from "./Icon";
import { useEscClose } from "../state/useEscClose";

const h = React.createElement;

export interface ConfirmDialogProps {
  /** 헤더 굵은 줄 — 대상 이름(파일명·템플릿명·계정 등) */
  name: string;
  /** 헤더 보조 줄 — 무슨 동작인지("첨부 삭제") */
  action: string;
  /** 본문. 배열이면 줄마다 한 문단 — 첫 줄이 강조, 나머지는 보조 설명. */
  message: string | string[];
  /** 되돌릴 수 없거나 보호 장치를 없애는 동작이면 true — 빨간 강조 + danger 버튼. */
  danger?: boolean;
  confirmLabel?: string;
  cancelLabel?: string;
  /** 헤더 아이콘(Icon 이름). 생략 시 danger=alert, 일반=info. */
  icon?: string;
  /** 처리 중이면 두 버튼 모두 잠근다. */
  busy?: boolean;
  onConfirm: () => void;
  onCancel: () => void;
}

/** 위험도에 따른 라벨·아이콘·클래스와 본문 줄 — 순수. 컴포넌트 밖에서 테스트한다. */
export interface ConfirmChrome {
  confirmLabel: string;
  cancelLabel: string;
  icon: string;
  confirmClass: string;
  /** 첫 줄(강조) 클래스 */
  leadClass: string;
  lines: string[];
}

export function confirmChrome(p: Pick<ConfirmDialogProps, "message" | "danger" | "confirmLabel" | "cancelLabel" | "icon">): ConfirmChrome {
  const danger = !!p.danger;
  const lines = (Array.isArray(p.message) ? p.message : [p.message]).filter((s) => !!s && s.trim() !== "");
  return {
    confirmLabel: p.confirmLabel || (danger ? "삭제" : "확인"),
    cancelLabel: p.cancelLabel || "취소",
    icon: p.icon || (danger ? "alert" : "info"),
    confirmClass: danger ? "pf-btn danger" : "pf-btn primary",
    leadClass: danger ? "pf-msg err" : "pf-msg ok",
    lines,
  };
}

export function ConfirmDialog(props: ConfirmDialogProps) {
  const { name, action, busy, onConfirm, onCancel } = props;
  const c = confirmChrome(props);
  useEscClose(onCancel);
  return h("div", { className: "pf-overlay", onMouseDown: onCancel },
    h("div", { className: "pf-card", onMouseDown: (e: React.MouseEvent) => e.stopPropagation() },
      h("div", { className: "pf-head" },
        h("span", { className: "pf-av" }, h(Icon, { name: c.icon })),
        h("div", { className: "pf-id" },
          h("div", { className: "pf-emp" }, name),
          h("div", { className: "pf-role" }, action)),
        h("button", { className: "icon-btn pf-x", onClick: onCancel, title: "닫기" }, h(Icon, { name: "x" }))),
      h("div", { className: "pf-body" },
        h("div", { className: "pf-sec" },
          c.lines.map((line, i) => h("div", { className: i === 0 ? c.leadClass : "pf-msg ok", key: i }, line)),
          h("div", { className: "pf-foot" },
            // 파괴적 동작이면 기본 포커스를 '취소'에 둔다 — Enter 연타로 지워지지 않도록.
            h("button", { className: "pf-btn", onClick: onCancel, disabled: busy, autoFocus: !!props.danger }, c.cancelLabel),
            h("button", { className: c.confirmClass, onClick: onConfirm, disabled: busy, autoFocus: !props.danger }, c.confirmLabel))))));
}
