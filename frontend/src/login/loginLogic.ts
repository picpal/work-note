/* loginLogic — LoginPage의 검증·제출 로직 분리 (api 주입으로 테스트 가능) */
import { ApiError } from "../api/http";
import type { AuthApi as AuthApiType, SignupForm } from "../api/auth";
import { MIN_PASSWORD_LENGTH } from "../lib/passwordPolicy";

export interface SignupInput extends SignupForm {
  password2: string;
}

/** 클라이언트 선검증 — 통과 시 null, 실패 시 사용자 메시지. 서버 검증(@Valid)이 최종. */
export function validateSignup(f: SignupInput): string | null {
  if (!f.emp.trim() || !f.name.trim()) return "사번과 이름을 입력하세요";
  if (f.password.length < MIN_PASSWORD_LENGTH) return "비밀번호는 " + MIN_PASSWORD_LENGTH + "자 이상이어야 합니다";
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
  // 빈/공백 이메일은 필드 생략(JSON 직렬화에서 undefined 제외) → 백엔드에 null 도착, DB에 '' 잔존 방지
  const payload = {
    emp: form.emp.trim(),
    name: form.name.trim(),
    email: form.email.trim() || undefined,
    password: form.password,
  };
  try {
    await api.signup(payload as SignupForm);
    return { done: true, error: null };
  } catch (e) {
    return { done: false, error: e instanceof ApiError ? e.message : "서버에 연결할 수 없습니다" };
  }
}
