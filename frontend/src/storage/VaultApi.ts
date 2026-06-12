/* VaultApi — 백엔드 REST 클라이언트. 쓰기 계열은 204 No Content, 오류는 {"error": string}. */
import type { VaultTree } from "../types";
import { req, ApiError } from "../api/http";

// 기존 import 경로 호환(useVaultSync 등) — ApiError 재수출.
export { ApiError };

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
