import { req } from "./http";

export interface TotpInfo {
  enabled: boolean;
  enforced: boolean;
  graceExpired: boolean;
  emailPresent: boolean;
}

/** GET /api/auth/me 응답. 백엔드 local 모드는 {id:"local", roleId:"admin", ...}. */
export interface Me {
  id: string;
  emp: string;
  name: string;
  roleId: string;
  email: string | null;
  caps: string[];
  totp?: TotpInfo;
}

export interface SignupForm {
  emp: string;
  name: string;
  email: string;
  password: string;
}

/** 로그인 응답 — 완전 인증(Me) 또는 부분 인증 신호. */
export type LoginResult = Me | { status: "2fa_required" };

export const AuthApi = {
  login: (emp: string, password: string) =>
    req<LoginResult>("/auth/login", { method: "POST", body: JSON.stringify({ emp, password }) }),
  verify2fa: (code: string) =>
    req<Me>("/auth/2fa/verify", { method: "POST", body: JSON.stringify({ code }) }),
  recoverRequest: (emp: string) =>
    req<void>("/auth/2fa/recover/request", { method: "POST", body: JSON.stringify({ emp }) }),
  recoverVerify: (emp: string, code: string) =>
    req<Me>("/auth/2fa/recover/verify", { method: "POST", body: JSON.stringify({ emp, code }) }),
  // 본인 2FA 관리
  totpSetup: () => req<{ otpauthUri: string }>("/me/2fa/setup", { method: "POST" }),
  totpConfirm: (code: string) =>
    req<void>("/me/2fa/confirm", { method: "POST", body: JSON.stringify({ code }) }),
  totpDisable: () => req<void>("/me/2fa", { method: "DELETE" }),
  // 기존 유지
  signup: (form: SignupForm) =>
    req<{ id: string; status: string }>("/auth/signup", { method: "POST", body: JSON.stringify(form) }),
  logout: () => req<void>("/auth/logout", { method: "POST" }),
  me: () => req<Me>("/auth/me"),
  changePassword: (currentPassword: string, newPassword: string) =>
    req<void>("/auth/change-password", { method: "POST", body: JSON.stringify({ currentPassword, newPassword }) }),
  updateProfile: (name: string, email: string) =>
    req<Me>("/auth/update-profile", { method: "POST", body: JSON.stringify({ name, email: email.trim() || undefined }) }),
};
