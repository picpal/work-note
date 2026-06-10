/* Admin screen 7: Security settings */
import React from "react";
import { ADMIN_SECURITY, Security as SecurityType } from "../data";
import { SecHead, Switch } from "../common";
import { Icon } from "../../components/Icon";

const { useState } = React;
const h = React.createElement;

export function Security({ toast }: { toast: (msg: string, icon?: string) => void }) {
  const [s, setS] = useState(() => ({ ...ADMIN_SECURITY }));
  const [dirty, setDirty] = useState(false);
  const set = (k: keyof SecurityType, v: number | boolean) => { setS((p) => ({ ...p, [k]: v })); setDirty(true); };
  const save = () => { setDirty(false); toast("보안 설정을 저장했습니다", "check"); };

  const numRow = (title: string, desc: string, key: keyof SecurityType, unit: string, min: number, max: number) => h("div", { className: "frow" },
    h("div", { className: "fmeta" }, h("div", { className: "ft" }, title), h("div", { className: "fd" }, desc)),
    h("div", { className: "fctl" },
      h("input", { className: "num-input", type: "number", value: s[key] as number, min, max, onChange: (e: React.ChangeEvent<HTMLInputElement>) => set(key, Number(e.target.value)) }),
      unit && h("span", { className: "unit" }, unit)));
  const toggleRow = (title: string, desc: string, key: keyof SecurityType) => h("div", { className: "frow" },
    h("div", { className: "fmeta" }, h("div", { className: "ft" }, title), h("div", { className: "fd" }, desc)),
    h("div", { className: "fctl" }, h(Switch, { on: s[key] as boolean, onChange: (v: boolean) => set(key, v) })));

  return h("div", { className: "apage" },
    h(SecHead, { title: "보안 설정", hint: "ISMS 정책 항목" }),
    h("div", { className: "panel" },
      h("div", { className: "panel-head" }, h(Icon, { name: "lock" }), "비밀번호 정책"),
      h("div", { className: "panel-body" },
        numRow("최소 길이", "비밀번호 최소 문자 수", "pwMinLen", "자", 6, 32),
        toggleRow("복잡도 요구", "영문 대/소문자·숫자·특수문자 조합 필수", "pwComplexity"),
        numRow("변경 주기", "비밀번호 강제 변경 주기 (0이면 미사용)", "pwRotateDays", "일", 0, 365))),
    h("div", { className: "panel", style: { marginTop: 16 } },
      h("div", { className: "panel-head" }, h(Icon, { name: "shield" }), "접근 · 세션"),
      h("div", { className: "panel-body" },
        numRow("로그인 실패 잠금", "연속 실패 시 계정 잠금 횟수", "lockAttempts", "회", 1, 10),
        numRow("세션 타임아웃", "유휴 상태 자동 로그아웃 시간", "sessionTimeout", "분", 5, 240),
        toggleRow("신규 가입 관리자 승인 필수", "가입 신청을 관리자가 승인해야 계정이 활성화됩니다", "requireApproval"))),
    h("div", { className: "btn-row", style: { marginTop: 18, justifyContent: "flex-end" } },
      h("button", { className: "btn", disabled: !dirty, onClick: () => { setS({ ...ADMIN_SECURITY }); setDirty(false); } }, "되돌리기"),
      h("button", { className: "btn primary", disabled: !dirty, onClick: save }, "변경 사항 저장"))
  );
}
