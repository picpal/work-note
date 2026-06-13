/* ProfileModal — user profile modal: edit name/email + change password. */
import { useState } from "react";
import React from "react";
import { Icon } from "./Icon";
import { AuthApi } from "../api/auth";
import type { Me } from "../api/auth";
import { storageMode } from "../storage";
import { ApiError } from "../api/http";
import { validatePasswordChange } from "./passwordValidation";
import { validateProfile } from "./profileValidation";
import { MIN_PASSWORD_LENGTH } from "../lib/passwordPolicy";

const h = React.createElement;

const PKEY = "wn.profile";
function loadProfile(emp: string | undefined) {
  try { const v = JSON.parse(localStorage.getItem(PKEY) || "{}"); return { name: v.name || "", email: v.email || (emp ? emp.toLowerCase() + "@corp.local" : "") }; }
  catch (e) { return { name: "", email: emp ? emp.toLowerCase() + "@corp.local" : "" }; }
}

interface ProfileModalProps {
  emp?: string;
  role?: string;
  name?: string; // http 모드 세션 사용자 이름 — 있으면 localStorage mock보다 우선
  email?: string | null; // http 모드 세션 이메일 — 있으면 localStorage mock보다 우선
  onClose: () => void;
  onSaved?: (me: Me) => void; // http 모드 프로필 저장 성공 시 세션(me) 갱신
  toast?: (message: string, icon: string) => void;
}

export function ProfileModal({ emp, role, name: sessionName, email: sessionEmail, onClose, onSaved, toast }: ProfileModalProps) {
  const init = loadProfile(emp);
  const [name, setName] = useState(sessionName || init.name);
  const [email, setEmail] = useState(storageMode === "http" ? (sessionEmail ?? "") : init.email);
  const [savedInfo, setSavedInfo] = useState(false);
  const [infoMsg, setInfoMsg] = useState<{ type: string; text: string } | null>(null);

  const [curPw, setCurPw] = useState("");
  const [newPw, setNewPw] = useState("");
  const [newPw2, setNewPw2] = useState("");
  const [pwMsg, setPwMsg] = useState<{ type: string; text: string } | null>(null);

  const saveInfo = async () => {
    if (storageMode !== "http") {
      try { localStorage.setItem(PKEY, JSON.stringify({ name: name.trim(), email: email.trim() })); } catch (e) {}
      setSavedInfo(true); setInfoMsg(null);
      toast && toast("프로필을 저장했습니다", "check");
      return;
    }
    const err = validateProfile(name);
    if (err) { setInfoMsg({ type: "err", text: err }); return; }
    try {
      const updated = await AuthApi.updateProfile(name.trim(), email.trim());
      setSavedInfo(true); setInfoMsg(null);
      onSaved && onSaved(updated);
      toast && toast("프로필을 저장했습니다", "check");
    } catch (e) {
      setInfoMsg({ type: "err", text: e instanceof ApiError ? e.message : "프로필 저장에 실패했습니다." });
    }
  };

  const changePw = async () => {
    if (storageMode !== "http") {
      // local 모드(무인증 단일 PC) — 서버 신원이 없어 변경 대상 자체가 없음(검증보다 선행)
      setPwMsg({ type: "err", text: "로컬 모드에서는 비밀번호를 변경할 수 없습니다." });
      return;
    }
    const err = validatePasswordChange(curPw, newPw, newPw2);
    if (err) { setPwMsg({ type: "err", text: err }); return; }
    try {
      await AuthApi.changePassword(curPw, newPw);
      setCurPw(""); setNewPw(""); setNewPw2("");
      setPwMsg(null);
      toast && toast("비밀번호를 변경했습니다", "check");
    } catch (e) {
      const msg = e instanceof ApiError ? e.message : "비밀번호 변경에 실패했습니다.";
      setPwMsg({ type: "err", text: msg });
    }
  };

  return h("div", { className: "pf-overlay", onMouseDown: onClose },
    h("div", { className: "pf-card", onMouseDown: (e: React.MouseEvent) => e.stopPropagation() },
      h("div", { className: "pf-head" },
        h("span", { className: "pf-av" }, h(Icon, { name: "user" })),
        h("div", { className: "pf-id" },
          h("div", { className: "pf-emp" }, emp),
          h("div", { className: "pf-role" }, role || "운영자")),
        h("button", { className: "icon-btn pf-x", onClick: onClose, title: "닫기" }, h(Icon, { name: "x" }))),
      h("div", { className: "pf-body" },
        // info section
        h("div", { className: "pf-sec" },
          h("div", { className: "pf-sec-label" }, "프로필 정보"),
          h("div", { className: "pf-field" },
            h("label", null, "사번"),
            h("input", { className: "pf-input", value: emp, disabled: true })),
          h("div", { className: "pf-field" },
            h("label", null, "이름"),
            h("input", { className: "pf-input", value: name, placeholder: "이름을 입력하세요",
              onChange: (e: React.ChangeEvent<HTMLInputElement>) => { setName(e.target.value); setSavedInfo(false); setInfoMsg(null); } })),
          h("div", { className: "pf-field" },
            h("label", null, "이메일"),
            h("input", { className: "pf-input", type: "email", value: email, placeholder: "name@corp.local",
              onChange: (e: React.ChangeEvent<HTMLInputElement>) => { setEmail(e.target.value); setSavedInfo(false); setInfoMsg(null); } })),
          infoMsg && h("div", { className: "pf-msg " + infoMsg.type }, infoMsg.text),
          h("div", { className: "pf-foot" },
            h("button", { className: "pf-btn primary", onClick: saveInfo }, savedInfo ? "저장됨" : "정보 저장"))),
        // password section
        h("div", { className: "pf-sec" },
          h("div", { className: "pf-sec-label" }, "비밀번호 변경"),
          h("div", { className: "pf-field" },
            h("label", null, "현재 비밀번호"),
            h("input", { className: "pf-input", type: "password", value: curPw, placeholder: "••••••••",
              onChange: (e: React.ChangeEvent<HTMLInputElement>) => { setCurPw(e.target.value); setPwMsg(null); } })),
          h("div", { className: "pf-field" },
            h("label", null, "새 비밀번호"),
            h("input", { className: "pf-input", type: "password", value: newPw, placeholder: MIN_PASSWORD_LENGTH + "자 이상",
              onChange: (e: React.ChangeEvent<HTMLInputElement>) => { setNewPw(e.target.value); setPwMsg(null); } })),
          h("div", { className: "pf-field" },
            h("label", null, "새 비밀번호 확인"),
            h("input", { className: "pf-input", type: "password", value: newPw2, placeholder: "••••••••",
              onChange: (e: React.ChangeEvent<HTMLInputElement>) => { setNewPw2(e.target.value); setPwMsg(null); } })),
          pwMsg && h("div", { className: "pf-msg " + pwMsg.type }, pwMsg.text),
          h("div", { className: "pf-foot" },
            h("button", { className: "pf-btn primary", onClick: changePw }, "비밀번호 변경"))))));
}
