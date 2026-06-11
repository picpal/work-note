/* VaultApi — 백엔드 REST 클라이언트. 쓰기 계열은 204 No Content, 오류는 {"error": string}. */
import type { VaultTree } from "../types";

const BASE = "/api";

/** HTTP 상태코드를 보존하는 API 오류 — 호출부에서 status 기반 판별(예: 부트스트랩 409 허용)에 사용. */
export class ApiError extends Error {
  constructor(msg: string, public status: number) {
    super(msg);
    this.name = "ApiError";
  }
}

async function req<T>(path: string, init?: RequestInit): Promise<T> {
  const res = await fetch(BASE + path, {
    ...init,
    headers: { "Content-Type": "application/json", ...(init?.headers as Record<string, string> | undefined) },
  });
  if (!res.ok) {
    const body = (await res.json().catch(() => ({}))) as { error?: string };
    throw new ApiError(body.error ?? `HTTP ${res.status}`, res.status);
  }
  return res.status === 204 ? (undefined as T) : ((await res.json()) as T);
}

export const VaultApi = {
  tree: () => req<VaultTree>("/tree"),
  create: (n: { id: string; parentId: string | null; type: "folder" | "note"; name: string; content?: string }) =>
    req<unknown>("/nodes", { method: "POST", body: JSON.stringify(n) }),
  update: (id: string, patch: { name?: string; content?: string; tags?: string[] }) =>
    req<void>(`/nodes/${id}`, { method: "PATCH", body: JSON.stringify(patch) }),
  move: (id: string, parentId: string | null) =>
    req<void>(`/nodes/${id}/move`, { method: "POST", body: JSON.stringify({ parentId }) }),
  trash: (id: string) => req<void>(`/nodes/${id}`, { method: "DELETE" }),
};

export type VaultApiType = typeof VaultApi;
