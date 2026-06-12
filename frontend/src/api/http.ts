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

export async function req<T>(path: string, init?: RequestInit): Promise<T> {
  const res = await fetch(BASE + path, {
    ...init,
    headers: { "Content-Type": "application/json", ...(init?.headers as Record<string, string> | undefined) },
  });
  if (!res.ok) {
    if (res.status === 401 && on401) on401();
    const body = (await res.json().catch(() => ({}))) as { error?: string };
    throw new ApiError(body.error ?? `HTTP ${res.status}`, res.status);
  }
  return res.status === 204 ? (undefined as T) : ((await res.json()) as T);
}
