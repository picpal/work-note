/* 공유 fetch 코어 — VaultApi/AuthApi/AdminApi가 공용. 세션 쿠키는 same-origin 자동 전송. */
const BASE = "/api";

/** HTTP 상태코드를 보존하는 API 오류 — 호출부에서 status 기반 판별(예: 부트스트랩 409 허용)에 사용. */
export class ApiError extends Error {
  constructor(message: string, public status: number) {
    super(message);
    this.name = "ApiError";
  }
}

/** 401 전역 핸들러(세션 만료 → login.html). login 앱은 설치하지 않는다 — 로그인 실패 401이 리다이렉트가 되면 안 됨. */
let on401: (() => void) | null = null;
export function setOn401(handler: (() => void) | null) {
  on401 = handler;
}

/** 관리자 2FA 유예 만료(403) 전역 핸들러 — 세션 중 만료 시 호출부가 me를 재조회해 강제 등록 게이트를 띄운다. */
let on2faRequired: (() => void) | null = null;
export function setOn2faRequired(handler: (() => void) | null) {
  on2faRequired = handler;
}

/** 이 응답이 관리자 2FA 강제 등록 차단(403)인가 — AuthFilter가 보내는 코드와 일치할 때만 true. */
export function is2faEnrollmentRequired(status: number, errorCode: string | undefined): boolean {
  return status === 403 && errorCode === "2fa_enrollment_required";
}

/** !res.ok 공통 처리 — 본문 파싱 후 전역 핸들러 발화, ApiError throw. req/reqForm 공용(DRY). */
async function fail(res: Response): Promise<never> {
  const body = (await res.json().catch(() => ({}))) as { error?: string };
  if (res.status === 401 && on401) on401();
  if (is2faEnrollmentRequired(res.status, body.error) && on2faRequired) on2faRequired();
  throw new ApiError(body.error ?? `HTTP ${res.status}`, res.status);
}

export async function req<T>(
  path: string,
  init?: Omit<RequestInit, "headers"> & { headers?: Record<string, string> },
): Promise<T> {
  const res = await fetch(BASE + path, {
    ...init,
    headers: { "Content-Type": "application/json", ...init?.headers },
  });
  if (!res.ok) await fail(res);
  return res.status === 204 ? (undefined as T) : ((await res.json()) as T);
}

/** multipart 업로드 — Content-Type을 설정하지 않아 브라우저가 boundary를 채운다. (req와 동일 오류 처리) */
export async function reqForm<T>(path: string, form: FormData): Promise<T> {
  const res = await fetch(BASE + path, { method: "POST", body: form });
  if (!res.ok) await fail(res);
  return res.status === 204 ? (undefined as T) : ((await res.json()) as T);
}
