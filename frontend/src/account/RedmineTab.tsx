/* RedmineTab — 프로필 내 Redmine 연동 키 등록/갱신/해제 섹션 (http 모드).
   관리자가 연동을 켰을 때만(st.enabled) 노출. 본인 키는 백엔드에서 검증 후 AES 저장. */
import { useState, useEffect } from "react";
import React from "react";
import { RedmineApi, type RedmineStatus } from "../api/redmine";
import { ApiError } from "../api/http";

const h = React.createElement;

export function RedmineTab({ toast }: { toast?: (m: string, i: string) => void }) {
  const [st, setSt] = useState<RedmineStatus | null>(null);
  const [token, setToken] = useState("");
  const [busy, setBusy] = useState(false);
  const [msg, setMsg] = useState<{ ok: boolean; text: string } | null>(null);

  useEffect(() => { void RedmineApi.status().then(setSt).catch(() => setSt(null)); }, []);

  const save = async () => {
    if (busy || !token.trim()) return;
    setBusy(true); setMsg(null);
    try {
      const next = await RedmineApi.setToken(token.trim());
      setSt(next); setToken("");
      setMsg({ ok: true, text: "연동되었습니다" + (next.redmineLogin ? ` (${next.redmineLogin})` : "") });
      toast?.("Redmine 연동 완료", "check");
    } catch (e) {
      const m = e instanceof ApiError ? e.message : "저장 실패";
      setMsg({ ok: false, text:
        m === "redmine_token_invalid" ? "유효하지 않은 키입니다"
        : m === "redmine_disabled" ? "관리자가 연동을 비활성화했습니다"
        : m });
    } finally { setBusy(false); }
  };

  const remove = async () => {
    if (busy) return;
    setBusy(true); setMsg(null);
    try {
      await RedmineApi.deleteToken();
      setSt((s) => (s ? { ...s, tokenPresent: false, redmineLogin: null } : s));
      toast?.("Redmine 연동을 해제했습니다", "check");
    } catch (e) {
      setMsg({ ok: false, text: e instanceof ApiError ? e.message : "해제 실패" });
    } finally { setBusy(false); }
  };

  // 상태 미로딩 또는 관리자 미활성 시 섹션 자체를 숨김(툴바 게이트와 일관).
  if (!st || !st.enabled) return null;

  return h("div", { className: "pf-sec" },
    h("div", { className: "pf-sec-label" }, "Redmine 연동"),
    st.tokenPresent &&
      h("div", { className: "pf-field" },
        h("label", null, "상태"),
        h("div", { style: { fontSize: 13, color: "var(--text-2)" } },
          "연동됨" + (st.redmineLogin ? ` · ${st.redmineLogin}` : ""))),
    h("div", { className: "pf-field" },
      h("label", null, st.tokenPresent ? "키 변경" : "API 키"),
      h("input", { className: "pf-input", type: "password", value: token, placeholder: "Redmine API 액세스 키",
        onChange: (e: React.ChangeEvent<HTMLInputElement>) => { setToken(e.target.value); setMsg(null); } })),
    msg && h("div", { className: "pf-msg", style: { color: msg.ok ? "var(--ok, #2e7d32)" : "var(--danger, #c62828)" } }, msg.text),
    h("div", { className: "pf-foot", style: { display: "flex", gap: 8 } },
      h("button", { className: "pf-btn primary", onClick: () => void save(), disabled: busy || !token.trim() },
        st.tokenPresent ? "키 갱신" : "연동"),
      st.tokenPresent &&
        h("button", { className: "pf-btn", onClick: () => void remove(), disabled: busy }, "해제")));
}
