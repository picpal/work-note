/* Admin screen 7: Security policy — read-only. 정책은 백엔드 코드/설정에 고정되어 변경 API가 없다. */
import React from "react";
import { SecHead } from "../common";
import { Icon } from "../../components/Icon";

const h = React.createElement;

const valueStyle: React.CSSProperties = { fontSize: 13, fontWeight: 600, color: "var(--ink)", textAlign: "right" };

function row(title: string, desc: string, value: string) {
  return h("div", { className: "frow" },
    h("div", { className: "fmeta" }, h("div", { className: "ft" }, title), h("div", { className: "fd" }, desc)),
    h("div", { className: "fctl" }, h("span", { style: valueStyle }, value)));
}

export function Security(_props: { toast: (msg: string, icon?: string) => void }) {
  return h("div", { className: "apage" },
    h(SecHead, { title: "보안 정책", hint: "서버 고정 정책 (읽기 전용)" }),
    h("div", { className: "changebar", style: { position: "static", marginTop: 0, marginBottom: 16 } },
      h(Icon, { name: "info" }),
      h("span", { className: "txt" }, "보안 정책은 서버에 고정되어 있습니다. 변경이 필요하면 서버 설정·코드 수준에서 다룹니다.")),
    h("div", { className: "panel" },
      h("div", { className: "panel-head" }, h(Icon, { name: "lock" }), "비밀번호 정책"),
      h("div", { className: "panel-body" },
        row("최소 길이", "비밀번호 최소 문자 수", "8자 (최대 128자)"),
        row("해시 저장", "비밀번호 저장 방식", "PBKDF2-SHA256 · 120,000 iterations · 사용자별 salt"),
        row("비밀번호 초기화", "관리자가 비밀번호를 리셋하면 해당 사용자의 기존 세션이 즉시 무효화됩니다", "기존 세션 즉시 무효화"))),
    h("div", { className: "panel", style: { marginTop: 16 } },
      h("div", { className: "panel-head" }, h(Icon, { name: "shield" }), "접근 · 세션"),
      h("div", { className: "panel-body" },
        row("세션 타임아웃", "유휴 상태 자동 로그아웃 시간", "30분"),
        row("신규 가입", "가입 신청은 관리자 승인 후 활성화됩니다 (pending → approve)", "관리자 승인 필수"),
        row("감사 기록", "로그인 실패와 모든 변이 작업이 감사 로그에 기록됩니다", "로그인 실패 · 전체 변이 기록")))
  );
}
