/* VaultApi — 백엔드 REST 클라이언트. 쓰기 계열은 204 No Content, 오류는 {"error": string}. */
import type { VaultTree, NotePii } from "../types";
import { req, ApiError } from "../api/http";

// 기존 import 경로 호환(useVaultSync 등) — ApiError 재수출.
export { ApiError };

/** 이동 미리보기 — 이동 전/후 공개 노출·스페이스·주체 접근 변화(백엔드 move-preview 계약). */
export interface MovePreview {
  publicBefore: boolean;
  publicAfter: boolean;
  crossSpace: boolean;
  fromSpace: string | null;
  toSpace: string | null;
  added: string[];
  removed: string[];
}

/** 휴지통 항목 — soft-delete된 노드(folder→name, note→title 중 하나만 non-null). */
export interface TrashItem {
  id: string;
  type: "folder" | "note";
  name?: string;
  title?: string;
}

export const VaultApi = {
  tree: () => req<VaultTree>("/tree"),
  create: (n: { id: string; parentId: string | null; type: "folder" | "note"; name: string; content?: string }) =>
    req<unknown>("/nodes", { method: "POST", body: JSON.stringify(n) }),
  update: (id: string, patch: { name?: string; content?: string; tags?: string[] }) =>
    req<{ pii?: NotePii }>(`/nodes/${id}`, { method: "PATCH", body: JSON.stringify(patch) }),
  // 내보내기 감사 핑(204) — fire-and-forget. 다운로드 자체는 클라에서 일어나므로 사후 통지만.
  logExport: (id: string, format: "pdf" | "md" | "copy") =>
    req<void>(`/nodes/${id}/export-log`, { method: "POST", body: JSON.stringify({ format }) }),
  move: (id: string, parentId: string | null) =>
    req<void>(`/nodes/${id}/move`, { method: "POST", body: JSON.stringify({ parentId }) }),
  movePreview: (id: string, parentId: string | null) =>
    req<MovePreview>(`/nodes/${encodeURIComponent(id)}/move-preview` + (parentId != null ? `?parentId=${encodeURIComponent(parentId)}` : "")),
  trash: (id: string) => req<void>(`/nodes/${id}`, { method: "DELETE" }),
  trashList: () => req<TrashItem[]>("/trash"),
  restore: (id: string) => req<void>(`/trash/${encodeURIComponent(id)}/restore`, { method: "POST" }),
  purge: (id: string) => req<void>(`/trash/${encodeURIComponent(id)}`, { method: "DELETE" }),
};

export type VaultApiType = typeof VaultApi;
