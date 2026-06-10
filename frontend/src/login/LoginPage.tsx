/* LoginPage.tsx — monotone auth screen (login + signup → admin approval) */
import { useState } from "react";
import React from "react";
import { Icon } from "../components/Icon";

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
  const [mode, setMode] = useState("login"); // login | signup | done
  const [emp, setEmp] = useState("");
  const [name, setName] = useState("");
  const [pw, setPw] = useState("");
  const [email, setEmail] = useState("");
  const [pw2, setPw2] = useState("");
  const [err, setErr] = useState("");

  const doLogin = (e: React.FormEvent | null) => {
    e && e.preventDefault();
    if (!emp.trim() || !pw) { setErr("사번과 비밀번호를 입력하세요."); return; }
    // prototype: no real auth — go to the notes app
    try { sessionStorage.setItem("wn.session", emp.trim()); } catch (e) {}
    location.href = "index.html";
  };
  const doSignup = (e: React.FormEvent | null) => {
    e && e.preventDefault();
    if (!emp.trim() || !name.trim() || !email.trim() || !pw || !pw2) { setErr("모든 항목을 입력하세요."); return; }
    if (pw !== pw2) { setErr("비밀번호가 일치하지 않습니다."); return; }
    setErr(""); setMode("done");
  };

  if (mode === "done") {
    return h("div", { className: "auth" }, h(ThemeBtn),
      h("div", { className: "auth-card" },
        h("div", { className: "auth-ok" },
          h("div", { className: "ok-ic" }, h(Icon, { name: "userCheck" })),
          h("h2", null, "가입 신청 완료"),
          h("p", null, "관리자 승인 후 계정이 활성화됩니다. 승인되면 사번과 비밀번호로 로그인할 수 있습니다."),
          h("button", { className: "auth-btn", onClick: () => { setMode("login"); setPw(""); setPw2(""); setEmail(""); setName(""); } }, "로그인으로 돌아가기"))));
  }

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
        h("button", { className: "auth-btn", type: "submit" }, isSignup ? "가입 신청" : "로그인")),
      isSignup
        ? h("div", { className: "auth-foot" }, "이미 계정이 있나요? ",
            h("button", { className: "auth-link", onClick: () => { setMode("login"); setErr(""); } }, "로그인"))
        : h(React.Fragment, null,
            h("div", { className: "auth-foot" }, "계정이 없나요? ",
              h("button", { className: "auth-link", onClick: () => { setMode("signup"); setErr(""); } }, "가입 신청")),
            h("div", { className: "auth-note" },
              h(Icon, { name: "shield" }),
              h("span", null, "폐쇄망 보안 정책에 따라 가입은 ", h("b", { style: { color: "var(--ink)" } }, "관리자 승인 후"), " 활성화됩니다.")))));
}
