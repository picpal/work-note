/* ConnectionLost — HTTP 모드에서 초기 데이터 로드 실패(백엔드 다운) 시의 전체 차단 화면.
   seed를 정상인 양 노출하지 않는다 — 편집이 저장되지 않으므로 진입 자체를 막고 재시도를 유도. */
import React from "react";
import { Icon } from "./Icon";

const h = React.createElement;

export function ConnectionLost({ onRetry }: { onRetry: () => void }) {
  return h("div", { className: "conn-lost" },
    h("div", { className: "cl-card" },
      h("div", { className: "cl-icon" }, h(Icon, { name: "alert" })),
      h("h2", null, "서버에 연결할 수 없습니다"),
      h("p", null, "편집 내용이 저장되지 않습니다. 서버 상태를 확인한 뒤 다시 시도하세요."),
      h("button", { className: "pf-btn primary", onClick: onRetry }, "다시 시도")));
}
