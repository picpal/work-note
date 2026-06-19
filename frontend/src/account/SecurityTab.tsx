/* SecurityTab — 본인 2FA 등록/해제. ProfileModal 내 보안 섹션으로 포함하거나 독립 모달로 사용 가능. */
import { useState } from "react";
import React from "react";
import { AuthApi } from "../api/auth";
import type { TotpInfo } from "../api/auth";
import { canEnroll, enrollBlockReason } from "../lib/totp2fa";
import { ApiError } from "../api/http";

const h = React.createElement;

interface SecurityTabProps {
  totp: TotpInfo;
  /** 2FA 상태 변경 후 me 갱신을 위해 호출. 호출부가 AuthApi.me()를 재요청해 totp를 갱신. */
  onChanged: () => void;
  toast?: (message: string, icon: string) => void;
}

/** 등록 플로우 단계: idle | setup(QR 표시+코드입력) | done */
type EnrollStep = "idle" | "setup" | "done";

export function SecurityTab({ totp, onChanged, toast }: SecurityTabProps) {
  const [enrollStep, setEnrollStep] = useState<EnrollStep>("idle");
  const [otpauthUri, setOtpauthUri] = useState<string>("");
  const [confirmCode, setConfirmCode] = useState("");
  const [qrFailed, setQrFailed] = useState(false);
  const [msg, setMsg] = useState<{ type: "ok" | "err"; text: string } | null>(null);
  const [busy, setBusy] = useState(false);

  const blockReason = enrollBlockReason(totp);

  const startEnroll = async () => {
    if (busy) return;
    setBusy(true); setMsg(null);
    try {
      const { otpauthUri: uri } = await AuthApi.totpSetup();
      setOtpauthUri(uri);
      setConfirmCode("");
      setQrFailed(false);
      setEnrollStep("setup");
    } catch (e) {
      setMsg({ type: "err", text: e instanceof ApiError ? e.message : "2FA 등록을 시작할 수 없습니다." });
    } finally {
      setBusy(false);
    }
  };

  const confirmEnroll = async (e: React.FormEvent) => {
    e.preventDefault();
    if (busy) return;
    if (!confirmCode.trim()) { setMsg({ type: "err", text: "인증 코드를 입력하세요" }); return; }
    setBusy(true); setMsg(null);
    try {
      await AuthApi.totpConfirm(confirmCode.trim());
      setEnrollStep("done");
      setMsg({ type: "ok", text: "2FA 등록이 완료되었습니다." });
      toast && toast("2FA 등록 완료", "check");
      onChanged();
    } catch (e) {
      setMsg({ type: "err", text: e instanceof ApiError ? e.message : "코드 확인에 실패했습니다." });
    } finally {
      setBusy(false);
    }
  };

  const doDisable = async () => {
    if (busy) return;
    if (!window.confirm("2FA를 비활성화하면 로그인 시 코드 인증이 생략됩니다. 계속하시겠습니까?")) return;
    setBusy(true); setMsg(null);
    try {
      await AuthApi.totpDisable();
      setMsg({ type: "ok", text: "2FA가 비활성화되었습니다." });
      toast && toast("2FA 비활성화됨", "check");
      onChanged();
    } catch (e) {
      setMsg({ type: "err", text: e instanceof ApiError ? e.message : "2FA 비활성화에 실패했습니다." });
    } finally {
      setBusy(false);
    }
  };

  // ---- 상태 표시 ----
  const statusBadge = totp.enabled
    ? h("span", { className: "sec-badge sec-badge--on" }, "활성")
    : h("span", { className: "sec-badge sec-badge--off" }, "미등록");

  // ---- QR + 코드 입력 단계 ----
  if (enrollStep === "setup") {
    // Base32 secret을 URI에서 추출(사용자 수동 입력용)
    const secretMatch = otpauthUri.match(/secret=([A-Z2-7]+)/i);
    const secretDisplay = secretMatch ? secretMatch[1] : "";

    return h("div", { className: "sec-tab" },
      h("div", { className: "pf-sec-label" }, "2단계 인증 등록"),
      h("p", { className: "sec-desc" }, "인증 앱(Google Authenticator 등)으로 QR을 스캔하거나 아래 키를 수동 입력하세요."),
      h("div", { className: "sec-qr-wrap" },
        qrFailed
          ? h("div", { className: "sec-qr-fail" }, "QR을 불러올 수 없습니다 — 아래 키를 인증 앱에 직접 입력하세요.")
          : h("img", { src: "/api/me/2fa/qr", alt: "TOTP QR 코드", className: "sec-qr", width: 180, height: 180,
              onError: () => setQrFailed(true) })),
      secretDisplay && h("div", { className: "sec-secret" },
        h("label", null, "수동 입력 키:"),
        h("code", { className: "sec-secret-code" }, secretDisplay)),
      h("form", { onSubmit: confirmEnroll, className: "sec-confirm-form" },
        h("div", { className: "pf-field" },
          h("label", null, "인증 코드 (6자리)"),
          h("input", { className: "pf-input", value: confirmCode, placeholder: "000000", maxLength: 6,
            inputMode: "numeric", autoFocus: true, autoComplete: "one-time-code",
            onChange: (e: React.ChangeEvent<HTMLInputElement>) => { setConfirmCode(e.target.value.replace(/\D/g, "")); setMsg(null); } })),
        msg && h("div", { className: "pf-msg " + msg.type }, msg.text),
        h("div", { className: "pf-foot" },
          h("button", { className: "pf-btn primary", type: "submit", disabled: busy }, "등록 확인"),
          h("button", { className: "pf-btn", type: "button", disabled: busy,
            onClick: () => { setEnrollStep("idle"); setMsg(null); setConfirmCode(""); } }, "취소"))));
  }

  // ---- 등록 완료 직후 (onChanged→me 재조회로 totp.enabled 갱신 전 과도기) ----
  if (enrollStep === "done" && !totp.enabled) {
    return h("div", { className: "sec-tab" },
      h("div", { className: "pf-sec-label" }, "2단계 인증 (TOTP)"),
      h("div", { className: "pf-msg ok" }, "2FA 등록이 완료되었습니다 — 잠시 후 상태가 갱신됩니다."));
  }

  // ---- 등록됨 상태 ----
  return h("div", { className: "sec-tab" },
    h("div", { className: "pf-sec-label" }, "2단계 인증 (TOTP)"),
    h("div", { className: "sec-status" },
      h("span", null, "현재 상태: "), statusBadge,
      totp.enforced && !totp.enabled && h("span", { className: "sec-warn" },
        totp.graceExpired ? " — 유예 기간 만료. 2FA 등록이 필요합니다." : " — 관리자 계정은 2FA 등록을 권장합니다.")),
    // 에러/성공 메시지
    msg && h("div", { className: "pf-msg " + msg.type }, msg.text),
    // 미등록: 등록 버튼
    !totp.enabled && h(React.Fragment, null,
      blockReason
        ? h("div", { className: "sec-block-reason" }, blockReason)
        : h("div", { className: "pf-foot" },
            h("button", { className: "pf-btn primary", disabled: busy || !canEnroll(totp),
              onClick: startEnroll }, "2FA 등록 시작"))),
    // 등록됨: 해제 버튼 (enforced admin은 백엔드가 403으로 차단 — 프런트는 버튼 표시만 조정)
    totp.enabled && h("div", { className: "pf-foot" },
      h("button", { className: "pf-btn danger", disabled: busy || totp.enforced,
        title: totp.enforced ? "관리자 계정은 2FA를 비활성화할 수 없습니다" : undefined,
        onClick: doDisable }, "2FA 비활성화")));
}
