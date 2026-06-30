/* TotpEnrollGate — 관리자 2FA 강제 등록 풀스크린 게이트. 메인 앱·AdminApp 공용.
   진입 판정(mustEnrollNow)은 호출부 책임. JSX 미사용. */
import React from "react";
import { Icon } from "./Icon";
import { SecurityTab } from "../account/SecurityTab";
import type { TotpInfo } from "../api/auth";

const h = React.createElement;

interface Props {
  totp: TotpInfo;
  onChanged: () => void;
  toast: (msg: string, icon?: string) => void;
  onLogout: () => void;
}

export function TotpEnrollGate({ totp, onChanged, toast, onLogout }: Props) {
  return h("div", { className: "totp-gate", style: {
    display: "flex", flexDirection: "column", alignItems: "center", justifyContent: "center",
    minHeight: "100vh", gap: 24, padding: 32, background: "var(--bg-0)",
  } },
    h("div", { className: "totp-gate-box", style: {
      maxWidth: 480, width: "100%", background: "var(--bg-1)", borderRadius: 12,
      boxShadow: "0 2px 16px rgba(0,0,0,.12)", padding: 32,
    } },
      h("div", { style: { display: "flex", alignItems: "center", gap: 12, marginBottom: 20 } },
        h("span", { className: "totp-gate-ic" }, h(Icon, { name: "shield" })),
        h("h2", { style: { margin: 0, fontSize: 18, fontWeight: 600 } }, "2단계 인증 등록 필요")),
      h("p", { style: { margin: "0 0 20px", color: "var(--text-2)", lineHeight: 1.6, fontSize: 14 } },
        "관리자 계정은 보안 정책에 따라 2FA(TOTP) 등록을 완료해야 계속 사용할 수 있습니다. 인증 앱을 준비하고 아래 절차를 따라 등록을 완료하세요. 인증 앱을 쓸 수 없으면 다른 관리자에게 2FA 초기화를 요청하세요."),
      h(SecurityTab, { totp, onChanged, toast }),
      h("div", { style: { marginTop: 20, borderTop: "1px solid var(--bd)", paddingTop: 16 } },
        h("button", { className: "btn sm", onClick: onLogout }, "로그아웃"))));
}
