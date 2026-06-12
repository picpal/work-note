/* loginLogic — LoginPage의 검증·제출 로직 분리 (api 주입으로 테스트 가능) */
import { ApiError } from "../api/http";
import type { AuthApi as AuthApiType, SignupForm } from "../api/auth";

export interface SignupInput extends SignupForm {
  password2: string;
}

/** 클라이언트 선검증 — 통과 시 null, 실패 시 사용자 메시지. 서버 검증(@Valid)이 최종. */
export function validateSignup(f: SignupInput): string | null {
  if (!f.emp.trim() || !f.name.trim()) return "사번과 이름을 입력하세요";
  if (f.password.length < 8) return "비밀번호는 8자 이상이어야 합니다";
  if (f.password !== f.password2) return "비밀번호가 일치하지 않습니다";
  return null;
}

export async function submitLogin(
  api: typeof AuthApiType, emp: string, password: string, onSuccess: () => void,
): Promise<string | null> {
  try {
    await api.login(emp.trim(), password);
    onSuccess();
    return null;
  } catch (e) {
    return e instanceof ApiError ? e.message : "서버에 연결할 수 없습니다";
  }
}

export async function submitSignup(
  api: typeof AuthApiType, form: SignupForm,
): Promise<{ done: boolean; error: string | null }> {
  try {
    await api.signup({ ...form, emp: form.emp.trim(), name: form.name.trim() });
    return { done: true, error: null };
  } catch (e) {
    return { done: false, error: e instanceof ApiError ? e.message : "서버에 연결할 수 없습니다" };
  }
}
