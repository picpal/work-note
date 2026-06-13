import { ApiError } from "../api/http";

/** 초기 트리 로드 실패를 "백엔드 다운(차단 화면)"으로 볼지 판정.
    http 모드에서 401(세션 만료 — on401 리다이렉트가 처리)만 제외한 모든 실패가 차단 대상.
    local 모드는 localStorage라 사실상 실패하지 않고, 실패해도 seed가 정상 — 차단하지 않는다. */
export function isBackendDown(e: unknown, mode: "http" | "local"): boolean {
  if (mode !== "http") return false;
  if (e instanceof ApiError && e.status === 401) return false;
  return true;
}
