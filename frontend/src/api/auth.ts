import { req } from "./http";

/** GET /api/auth/me 응답. 백엔드 local 모드는 {id:"local", roleId:"admin", ...}. */
export interface Me {
  id: string;
  emp: string;
  name: string;
  roleId: string;
  caps: string[];
}

export interface SignupForm {
  emp: string;
  name: string;
  email: string;
  password: string;
}

export const AuthApi = {
  login: (emp: string, password: string) =>
    req<Me>("/auth/login", { method: "POST", body: JSON.stringify({ emp, password }) }),
  signup: (form: SignupForm) =>
    req<{ id: string; status: string }>("/auth/signup", { method: "POST", body: JSON.stringify(form) }),
  logout: () => req<void>("/auth/logout", { method: "POST" }),
  me: () => req<Me>("/auth/me"),
};
