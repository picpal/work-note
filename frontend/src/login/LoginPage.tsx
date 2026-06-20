/* LoginPage.tsx — monotone auth screen (login + signup → admin approval + 2FA) */
import { useState, useEffect } from "react";
import React from "react";
import { Icon } from "../components/Icon";
import { AuthApi } from "../api/auth";
import { validateSignup, submitLogin2fa, submitVerify2fa, submitSignup, submitRecover } from "./loginLogic";

const h = React.createElement;

function ThemeBtn() {
  const [dark, setDark] = useState(document.documentElement.getAttribute("data-theme") === "dark");
  const toggle = () => {
    const next = dark ? "light" : "dark";
    document.documentElement.setAttribute("data-theme", next);
    try { localStorage.setItem("wn.theme", next); } catch (e) {}
    setDark(!dark);
  };
  return h("button", { className: "icon-btn auth-theme", title: dark ? "라이트 모드" : "다크 모드", onClick: toggle },
    h(Icon, { name: dark ? "sun" : "moon" }));
}

export function LoginPage() {
  // mode: login | signup | done | otp | recovery | recovery-sent
  const [mode, setMode] = useState("login");
  const [emp, setEmp] = useState("");
  const [name, setName] = useState("");
  const [pw, setPw] = useState("");
  const [email, setEmail] = useState("");
  const [pw2, setPw2] = useState("");
  const [otpCode, setOtpCode] = useState("");
  const [recoverEmp, setRecoverEmp] = useState("");
  const [recoverCode, setRecoverCode] = useState("");
  const [err, setErr] = useState("");
  const [busy, setBusy] = useState(false);

  // 이미 로그인된 세션(또는 백엔드 local 모드)이면 로그인 화면을 건너뛴다.
  useEffect(() => {
    AuthApi.me().then(() => { location.href = "index.html"; }).catch(() => {});
  }, []);

  const doLogin = async (e: React.FormEvent | null) => {
    e && e.preventDefault();
    if (busy) return;
    if (!emp.trim() || !pw) { setErr("사번과 비밀번호를 입력하세요"); return; }
    setErr(""); setBusy(true);
    const out = await submitLogin2fa(AuthApi, emp, pw);
    if (out.kind === "ok") { location.href = "index.html"; return; }
    if (out.kind === "2fa") { setBusy(false); setMode("otp"); setErr(""); return; }
    setBusy(false); setErr(out.message);
  };

  const doVerifyOtp = async (e: React.FormEvent | null) => {
    e && e.preventDefault();
    if (busy) return;
    if (!otpCode.trim()) { setErr("인증 코드를 입력하세요"); return; }
    setErr(""); setBusy(true);
    const error = await submitVerify2fa(AuthApi, otpCode);
    if (error) { setBusy(false); setErr(error); }
    else { location.href = "index.html"; }
  };

  const doSignup = async (e: React.FormEvent | null) => {
    e && e.preventDefault();
    if (busy) return;
    const invalid = validateSignup({ emp, name, email, password: pw, password2: pw2 });
    if (invalid) { setErr(invalid); return; }
    setErr(""); setBusy(true);
    const out = await submitSignup(AuthApi, { emp, name, email, password: pw });
    setBusy(false);
    if (out.done) setMode("done");
    else setErr(out.error ?? "");
  };

  const doRecoverRequest = async (e: React.FormEvent | null) => {
    e && e.preventDefault();
    if (busy) return;
    if (!recoverEmp.trim()) { setErr("사번을 입력하세요"); return; }
    setErr(""); setBusy(true);
    try {
      await AuthApi.recoverRequest(recoverEmp.trim());
    } catch (e) {
      // 균등 응답: 실패해도 사용자에게 같은 안내
    }
    setBusy(false);
    setMode("recovery-sent");
    setErr("");
  };

  const doRecoverVerify = async (e: React.FormEvent | null) => {
    e && e.preventDefault();
    if (busy) return;
    if (!recoverCode.trim()) { setErr("복구 코드를 입력하세요"); return; }
    setErr(""); setBusy(true);
    const error = await submitRecover(AuthApi, recoverEmp, recoverCode);
    if (error) { setBusy(false); setErr(error); }
    else { location.href = "index.html"; }
  };

  // ---- done (signup complete) ----
  if (mode === "done") {
    return h("div", { className: "auth" }, h(ThemeBtn),
      h("div", { className: "auth-card" },
        h("div", { className: "auth-ok" },
          h("div", { className: "ok-ic" }, h(Icon, { name: "userCheck" })),
          h("h2", null, "가입 신청 완료"),
          h("p", null, "관리자 승인 후 계정이 활성화됩니다. 승인되면 사번과 비밀번호로 로그인할 수 있습니다."),
          h("button", { className: "auth-btn", onClick: () => { setMode("login"); setPw(""); setPw2(""); setEmail(""); setName(""); } }, "로그인으로 돌아가기"))));
  }

  // ---- OTP 인증 단계 ----
  if (mode === "otp") {
    return h("div", { className: "auth" }, h(ThemeBtn),
      h("div", { className: "auth-card" },
        h("div", { className: "auth-brand" },
          h("div", { className: "brand-mark" }, "W"),
          h("div", { className: "nm" }, "WorkNote"),
          h("span", { className: "tag" }, "사내 폐쇄망")),
        h("h1", { className: "auth-title" }, "2단계 인증"),
        h("p", { className: "auth-sub" }, "인증 앱(Google Authenticator 등)의 6자리 코드를 입력하세요."),
        h("form", { onSubmit: doVerifyOtp },
          h("div", { className: "auth-field" },
            h("label", null, "인증 코드"),
            h("input", { className: "auth-input", value: otpCode, placeholder: "000000", maxLength: 6,
              inputMode: "numeric", autoFocus: true, autoComplete: "one-time-code",
              onChange: (e: React.ChangeEvent<HTMLInputElement>) => { setOtpCode(e.target.value.replace(/\D/g, "")); setErr(""); } })),
          err && h("div", { className: "auth-err" }, err),
          h("button", { className: "auth-btn", type: "submit", disabled: busy }, "인증")),
        h("div", { className: "auth-foot" },
          h("button", { className: "auth-link", onClick: () => { setMode("recovery"); setErr(""); setRecoverEmp(emp); setRecoverCode(""); } }, "복구 코드로 로그인")),
        h("div", { className: "auth-foot" },
          h("button", { className: "auth-link", onClick: () => { setMode("login"); setErr(""); setOtpCode(""); } }, "로그인으로 돌아가기"))));
  }

  // ---- 복구: emp 입력 ----
  if (mode === "recovery") {
    return h("div", { className: "auth" }, h(ThemeBtn),
      h("div", { className: "auth-card" },
        h("div", { className: "auth-brand" },
          h("div", { className: "brand-mark" }, "W"),
          h("div", { className: "nm" }, "WorkNote"),
          h("span", { className: "tag" }, "사내 폐쇄망")),
        h("h1", { className: "auth-title" }, "복구 코드 로그인"),
        h("p", { className: "auth-sub" }, "등록된 이메일로 복구 코드를 발송합니다. 코드는 10분간 유효합니다."),
        h("form", { onSubmit: doRecoverRequest },
          h("div", { className: "auth-field" },
            h("label", null, "사번"),
            h("input", { className: "auth-input", value: recoverEmp, placeholder: "사번을 입력하세요", autoFocus: true,
              onChange: (e: React.ChangeEvent<HTMLInputElement>) => { setRecoverEmp(e.target.value); setErr(""); } })),
          err && h("div", { className: "auth-err" }, err),
          h("button", { className: "auth-btn", type: "submit", disabled: busy }, "복구 코드 발송")),
        h("div", { className: "auth-foot" },
          h("button", { className: "auth-link", onClick: () => { setMode("login"); setErr(""); } }, "로그인으로 돌아가기"))));
  }

  // ---- 복구: 코드 입력 ----
  if (mode === "recovery-sent") {
    return h("div", { className: "auth" }, h(ThemeBtn),
      h("div", { className: "auth-card" },
        h("div", { className: "auth-brand" },
          h("div", { className: "brand-mark" }, "W"),
          h("div", { className: "nm" }, "WorkNote"),
          h("span", { className: "tag" }, "사내 폐쇄망")),
        h("h1", { className: "auth-title" }, "복구 코드 입력"),
        h("p", { className: "auth-sub" }, "이메일로 발송된 8자리 복구 코드를 입력하세요. 복구 성공 후 2FA를 다시 등록해야 합니다."),
        h("form", { onSubmit: doRecoverVerify },
          h("div", { className: "auth-field" },
            h("label", null, "복구 코드"),
            h("input", { className: "auth-input", value: recoverCode, placeholder: "12345678", maxLength: 8,
              inputMode: "numeric", autoFocus: true,
              onChange: (e: React.ChangeEvent<HTMLInputElement>) => { setRecoverCode(e.target.value.replace(/\D/g, "")); setErr(""); } })),
          err && h("div", { className: "auth-err" }, err),
          h("button", { className: "auth-btn", type: "submit", disabled: busy }, "복구 로그인")),
        h("div", { className: "auth-foot" },
          h("button", { className: "auth-link", onClick: () => { setMode("recovery"); setErr(""); setRecoverCode(""); } }, "코드 재발송")),
        h("div", { className: "auth-foot" },
          h("button", { className: "auth-link", onClick: () => { setMode("login"); setErr(""); } }, "로그인으로 돌아가기"))));
  }

  // ---- login / signup ----
  const isSignup = mode === "signup";
  return h("div", { className: "auth" }, h(ThemeBtn),
    h("div", { className: "auth-card" },
      h("div", { className: "auth-brand" },
        h("div", { className: "brand-mark" }, "W"),
        h("div", { className: "nm" }, "WorkNote"),
        h("span", { className: "tag" }, "사내 폐쇄망")),
      h("h1", { className: "auth-title" }, isSignup ? "가입 신청" : "로그인"),
      h("p", { className: "auth-sub" }, isSignup
        ? "신청 후 관리자 승인을 거쳐 계정이 활성화됩니다."
        : "사번과 비밀번호를 입력해 로그인하세요."),
      h("form", { onSubmit: isSignup ? doSignup : doLogin },
        h("div", { className: "auth-field" },
          h("label", null, "사번"),
          h("input", { className: "auth-input", value: emp, placeholder: "예: S2026-0142", autoFocus: true,
            onChange: (e: React.ChangeEvent<HTMLInputElement>) => { setEmp(e.target.value); setErr(""); } })),
        isSignup && h("div", { className: "auth-field" },
          h("label", null, "이름"),
          h("input", { className: "auth-input", value: name, placeholder: "이름을 입력하세요",
            onChange: (e: React.ChangeEvent<HTMLInputElement>) => { setName(e.target.value); setErr(""); } })),
        isSignup && h("div", { className: "auth-field" },
          h("label", null, "이메일"),
          h("input", { className: "auth-input", type: "email", value: email, placeholder: "name@corp.local",
            onChange: (e: React.ChangeEvent<HTMLInputElement>) => { setEmail(e.target.value); setErr(""); } })),
        h("div", { className: "auth-field" },
          h("label", null, "비밀번호"),
          h("input", { className: "auth-input", type: "password", value: pw, placeholder: "••••••••",
            onChange: (e: React.ChangeEvent<HTMLInputElement>) => { setPw(e.target.value); setErr(""); } })),
        isSignup && h("div", { className: "auth-field" },
          h("label", null, "비밀번호 확인"),
          h("input", { className: "auth-input", type: "password", value: pw2, placeholder: "••••••••",
            onChange: (e: React.ChangeEvent<HTMLInputElement>) => { setPw2(e.target.value); setErr(""); } })),
        err && h("div", { className: "auth-err" }, err),
        h("button", { className: "auth-btn", type: "submit", disabled: busy }, isSignup ? "가입 신청" : "로그인")),
      isSignup
        ? h("div", { className: "auth-foot" }, "이미 계정이 있나요? ",
            h("button", { className: "auth-link", onClick: () => { setMode("login"); setErr(""); setPw(""); setPw2(""); } }, "로그인"))
        : h(React.Fragment, null,
            h("div", { className: "auth-foot" }, "계정이 없나요? ",
              h("button", { className: "auth-link", onClick: () => { setMode("signup"); setErr(""); setPw(""); setPw2(""); } }, "가입 신청")),
            h("div", { className: "auth-note" },
              h(Icon, { name: "shield" }),
              h("span", null, "폐쇄망 보안 정책에 따라 가입은 ", h("b", { style: { color: "var(--ink)" } }, "관리자 승인 후"), " 활성화됩니다.")))));
}
